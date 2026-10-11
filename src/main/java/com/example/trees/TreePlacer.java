package com.example.trees;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import com.example.worldgen.RoadMarker;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Property;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.StructureWorldAccess;

/**
 * Places one TreeGenerator object, following the handoff's WORLDGEN.md:
 *
 * <ul>
 *   <li><b>Ground.</b> The paste point goes one block above the top ground block,
 *       on ground of the allowed groups; never on water except mangroves, which
 *       stand on the bed in water of the right depth.</li>
 *   <li><b>Slope.</b> Ground under the trunk footprint ({@code base + 1}) may vary
 *       by one block, which the sunk base hides; by two, the object is lowered
 *       one; more, the spot is refused. A fallen log needs ground level within a
 *       block along its whole length, and rests on the lowest of it.</li>
 *   <li><b>Spacing</b> by crown and trunk radii ({@link PlacedObjects}).</li>
 *   <li><b>Air is never written</b> -- templates hold no air, which is
 *       {@code //paste -a} -- so the terrain and the water between mangrove roots
 *       stay.</li>
 * </ul>
 *
 * <p>Pasting is careful about what is already there:
 * <ul>
 *   <li>above ground an object does not cut into terrain; only its sunk base
 *       (below the paste point) and the bottom two layers of trunk do, which is
 *       what seats it on a slope;</li>
 *   <li>no wood into existing wood, and leaves never replace wood -- a crowded
 *       crown loses leaves, not another tree's branch;</li>
 *   <li>only mangroves go into water, and a waterloggable block put into water
 *       is waterlogged, so the roots still stand in it;</li>
 *   <li>roads keep three blocks of headroom, and the road marker is never
 *       overwritten (snow and the town painter rely on it);</li>
 *   <li>a ground plant's {@code layers} is set to the layer block it stands on,
 *       as Conquest's own placement does, so it sits on a part layer instead of
 *       floating over it; and a ground plant with nothing under it is left out;</li>
 *   <li>a double plant cut in half by a trunk loses its other half too.</li>
 * </ul>
 *
 * <p>Inside chunk generation every block stays within the 3x3 chunks vanilla
 * lets a feature write to, which every object fits from some paste point in
 * the centre chunk; the 49 that reach further than 16 blocks have their paste
 * point moved in from the chunk edge just enough.
 */
final class TreePlacer {

    static final int FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
    /** Blocks of headroom kept above a road surface. */
    private static final int ROAD_CLEARANCE = 3;
    /** Most ground variation under a footprint beyond the sunk depth, whatever the gradient allows. */
    private static final int MAX_BURY = 6;

    /** Why a spot was turned down; counted for {@code /fmg trees stats}. */
    enum Fail { MISSING, WINDOW, NO_GROUND, WATER, DEPTH, GROUND, SLOPE, ROAD, HEIGHT,
        NOT_NEAR_WATER, NOT_NEAR_SEA, CANOPY, SPACING, WOOD, BIOME }

    /**
     * Placement rules for one attempt. {@code spacing <= 0}: do not check spacing.
     * {@code slope}: how far the ground under the footprint may vary beyond the
     * object's sunk depth.
     */
    record Rules(byte layer, List<String> ground, Optional<TreeObjectConfig.Water> water, float spacing,
                 int nearWater, int nearSea, float openSky, int slope, float gradient) {
    }

    /** What the placer needs from the world. */
    static final class Ctx {
        final StructureWorldAccess world;
        final ChunkPos centre;          // null outside chunk generation (e.g. /place)
        final TreeGround ground;
        final int seaLevel;
        final Random random;
        final PlacedObjects placed;
        final StructureTemplateManager templates;
        final int bottom, top;
        /** What the last refusal ran into (the ground group, the slope), for the stats. */
        String detail;

        Ctx(StructureWorldAccess world, TreeGround ground, int seaLevel, Random random) {
            this.world = world;
            this.centre = world instanceof ChunkRegion region ? region.getCenterPos() : null;
            this.ground = ground;
            this.seaLevel = seaLevel;
            this.random = random;
            this.placed = PlacedObjects.of(world.toServerWorld());
            this.templates = world.toServerWorld().getStructureTemplateManager();
            this.bottom = world.getBottomY();
            this.top = world.getTopY();
        }

        boolean writable(int x, int z) {
            return centre == null
                    || (Math.abs((x >> 4) - centre.x) <= 1 && Math.abs((z >> 4) - centre.z) <= 1);
        }

        /**
         * Whether the column's chunk is part of the region. Asked of the region
         * rather than assumed: vanilla loads 8 chunks around a decorating chunk,
         * but performance mods cut that (ArdaCraft's set logs "features task
         * radius: 8 -> 1"), and reading past it throws.
         */
        boolean readable(int x, int z) {
            return centre == null || world.isChunkLoaded(x >> 4, z >> 4);
        }
    }

    // ---- stats ---------------------------------------------------------------------

    private static final Map<String, LongAdder> STATS = new ConcurrentHashMap<>();

    static void count(String key) {
        STATS.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    static Map<String, LongAdder> stats() {
        return STATS;
    }

    /** Time spent placing for one biome layer, kept in microseconds and shown as ms. */
    static void time(String key, long nanos) {
        STATS.computeIfAbsent(key + " us", k -> new LongAdder()).add(nanos / 1000);
    }

    // ---- placing -------------------------------------------------------------------

    /**
     * Try to place {@code spec} with its paste point at column {@code (ox, oz)},
     * turned {@code rot} quarter-turns. Returns null on success, else why not.
     */
    static Fail place(Ctx c, TreeObjectConfig.ObjectSpec spec, Rules rules, int ox, int oz, int rot) {
        c.detail = null;
        List<Integer> a = spec.anchor();
        Optional<TreeShapes.Shape> found = TreeShapes.get(c.templates, spec.template(), a.get(0), a.get(1), a.get(2));
        if (found.isEmpty()) return Fail.MISSING;
        TreeShapes.Shape shape = found.get();

        // rotated horizontal extent, relative to the paste point
        int x0 = Math.min(TreeShapes.rx(shape.minX, shape.minZ, rot), TreeShapes.rx(shape.maxX, shape.maxZ, rot));
        int x1 = Math.max(TreeShapes.rx(shape.minX, shape.minZ, rot), TreeShapes.rx(shape.maxX, shape.maxZ, rot));
        int z0 = Math.min(TreeShapes.rz(shape.minX, shape.minZ, rot), TreeShapes.rz(shape.maxX, shape.maxZ, rot));
        int z1 = Math.max(TreeShapes.rz(shape.minX, shape.minZ, rot), TreeShapes.rz(shape.maxX, shape.maxZ, rot));

        // keep every block inside the 3x3 chunks the feature may write to
        if (c.centre != null) {
            int cx = c.centre.getStartX(), cz = c.centre.getStartZ();
            int loX = Math.max(cx, cx - 16 - x0), hiX = Math.min(cx + 15, cx + 31 - x1);
            int loZ = Math.max(cz, cz - 16 - z0), hiZ = Math.min(cz + 15, cz + 31 - z1);
            if (loX > hiX || loZ > hiZ) return Fail.WINDOW;
            ox = Math.max(loX, Math.min(hiX, ox));
            oz = Math.max(loZ, Math.min(hiZ, oz));
        }

        TreeGround.Column col = c.ground.column(c.world, ox, oz);
        if (col == null || "lava".equals(col.group())) return Fail.NO_GROUND;
        if (col.road()) return Fail.ROAD;

        boolean wet = rules.water().isPresent();
        int y0;
        int footX0, footX1, footZ0, footZ1;
        if (wet) {
            TreeObjectConfig.Water w = rules.water().get();
            if (!col.underwater()) return Fail.WATER;
            int depth = col.waterDepth();
            if (depth < w.minDepth() || depth > w.maxDepth()) return Fail.DEPTH;
            if (w.sea() && col.waterTop() != c.seaLevel - 1) return Fail.WATER;
            if ("ice".equals(col.group())) return Fail.GROUND;
            // y = 0 is the lowest water layer: the paste point sits on the bed
            y0 = col.yTop() + 1;
            footX0 = ox - 1;
            footX1 = ox + 1;
            footZ0 = oz - 1;
            footZ1 = oz + 1;
        } else {
            if (col.underwater()) return Fail.WATER;
            if (!rules.ground().isEmpty() && !rules.ground().contains(col.group())) {
                c.detail = col.group();
                return Fail.GROUND;
            }
            if (rules.layer() == PlacedObjects.LOG) {
                // a fallen log needs level ground under its whole length
                int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                for (int x = ox + x0; x <= ox + x1; x++) {
                    for (int z = oz + z0; z <= oz + z1; z++) {
                        if (!c.readable(x, z)) return Fail.WINDOW;
                        TreeGround.Column g = c.ground.column(c.world, x, z);
                        if (g == null || g.underwater()) return Fail.WATER;
                        if (g.road()) return Fail.ROAD;
                        lo = Math.min(lo, g.yTop());
                        hi = Math.max(hi, g.yTop());
                    }
                }
                if (hi - lo > 1) {
                    c.detail = Integer.toString(hi - lo);
                    return Fail.SLOPE;
                }
                // rest on the lowest ground: a log sunk into the higher part
                // reads as bedded in, floating over the lower part does not
                y0 = lo + 1;
                footX0 = ox + x0;
                footX1 = ox + x1;
                footZ0 = oz + z0;
                footZ1 = oz + z1;
            } else {
                int r = (int) Math.ceil(spec.base() + 1);
                int lo = col.yTop(), hi = col.yTop();
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx * dx + dz * dz > r * r || (dx == 0 && dz == 0)) continue;
                        if (!c.readable(ox + dx, oz + dz)) return Fail.WINDOW;
                        TreeGround.Column g = c.ground.column(c.world, ox + dx, oz + dz);
                        if (g == null) return Fail.NO_GROUND;
                        if (g.underwater()) return Fail.WATER;
                        if (g.road()) return Fail.ROAD;
                        lo = Math.min(lo, g.yTop());
                        hi = Math.max(hi, g.yTop());
                    }
                }
                // The sunk base is what hides uneven ground ("vary by at most
                // 1 block; the sunk base hides that much"), so an object takes
                // as much slope as it is sunk, plus the rule's allowance. It is
                // seated so the bottom of its sunk base meets the lowest ground
                // under it -- never floating downhill -- and is bedded into the
                // uphill side by no more than it has to be; past the trunk's
                // bottom two layers the hillside simply covers it.
                int range = hi - lo;
                int sunk = Math.max(0, spec.depth());
                // a block allowance, or a steepness across the footprint's
                // width, whichever is more: a wide trunk spans more rise on the
                // same hillside, and that should not make the hillside too steep
                int allowed = Math.max(sunk + Math.max(0, rules.slope()),
                        (int) Math.round(rules.gradient() * 2 * r));
                // but never bed a trunk deeper than this into the uphill side:
                // a big tree seated at the foot of a steep footprint would sink
                // its lower crown into the hill (the mix offers a smaller tree)
                allowed = Math.min(allowed, sunk + MAX_BURY);
                if (range > allowed) {
                    c.detail = Integer.toString(range);
                    return Fail.SLOPE;
                }
                y0 = lo + 1 + Math.min(Math.max(range - 1, 0), sunk);
                footX0 = ox - r;
                footX1 = ox + r;
                footZ0 = oz - r;
                footZ1 = oz + r;
            }
        }
        if (y0 + shape.minY <= c.bottom || y0 + shape.maxY >= c.top) return Fail.HEIGHT;

        if (rules.nearWater() > 0 && !near(c, ox, oz, rules.nearWater(), false)) return Fail.NOT_NEAR_WATER;
        if (rules.nearSea() > 0 && !near(c, ox, oz, rules.nearSea(), true)) return Fail.NOT_NEAR_SEA;

        if (rules.openSky() < 1f && underCanopy(c, ox, y0, oz) && c.random.nextFloat() >= rules.openSky()) {
            return Fail.CANOPY;
        }

        PlacedObjects.Placed me = new PlacedObjects.Placed(ox, oz, rules.layer(), spec.crown(), spec.base(),
                footX0, footX1, footZ0, footZ1);
        if (rules.spacing() > 0 && !c.placed.clear(me, rules.spacing())) return Fail.SPACING;

        BlockPos.Mutable m = new BlockPos.Mutable();
        // no wood into existing wood: catches what the spacing record cannot see,
        // e.g. objects from before a restart
        for (int i = 0; i < shape.size(); i++) {
            if (shape.kind[i] != TreeShapes.WOOD || shape.dy[i] < 0 || shape.dy[i] > 2) continue;
            int wx = ox + TreeShapes.rx(shape.dx[i], shape.dz[i], rot);
            int wz = oz + TreeShapes.rz(shape.dx[i], shape.dz[i], rot);
            if (!c.writable(wx, wz)) continue;
            BlockState have = c.world.getBlockState(m.set(wx, y0 + shape.dy[i], wz));
            if (!have.isAir() && TreeShapes.kindOf(have) == TreeShapes.WOOD
                    && c.ground.group(c.world, m, have) == null) {
                return Fail.WOOD;
            }
        }

        paste(c, shape, rot, ox, y0, oz, wet, m);
        c.placed.add(me);
        return null;
    }

    private static void paste(Ctx c, TreeShapes.Shape shape, int rot, int ox, int y0, int oz,
                              boolean wet, BlockPos.Mutable m) {
        Long2IntOpenHashMap roads = new Long2IntOpenHashMap();
        roads.defaultReturnValue(Integer.MIN_VALUE + 1);
        BlockState[] states = shape.states[rot & 3];
        for (int i = 0; i < shape.size(); i++) {
            int dy = shape.dy[i];
            int wx = ox + TreeShapes.rx(shape.dx[i], shape.dz[i], rot);
            int wy = y0 + dy;
            int wz = oz + TreeShapes.rz(shape.dx[i], shape.dz[i], rot);
            if (!c.writable(wx, wz) || wy <= c.bottom || wy >= c.top) continue;

            // headroom over roads, for the low part of the object
            if (dy <= 12) {
                int road = roadY(c, roads, wx, y0, wz, m);
                if (road != Integer.MIN_VALUE && wy >= road - 1 && wy <= road + ROAD_CLEARANCE) continue;
            }

            byte kind = shape.kind[i];
            BlockState want = states[i];
            BlockState have = c.world.getBlockState(m.set(wx, wy, wz));
            if (RoadMarker.is(have)) continue;

            FluidState fluid = have.getFluidState();
            if (!fluid.isEmpty()) {
                if (!wet || !fluid.isIn(FluidTags.WATER)) continue;
                if (fluid.isStill()) {
                    want = withNamed(want, "waterlogged", "true");
                }
            } else if (!have.isAir()) {
                String ground = c.ground.group(c.world, m, have);
                if (ground != null) {
                    // terrain: only the sunk base, and the trunk's bottom two layers
                    if (!(dy < 0 || (kind == TreeShapes.WOOD && dy <= 1))) continue;
                    clearCover(c, wx, wy + 1, wz);
                } else {
                    byte there = TreeShapes.kindOf(have);
                    if (there == TreeShapes.WOOD) continue;                         // never wood in wood
                    if (there == TreeShapes.LEAF && kind != TreeShapes.WOOD) continue; // leaves keep their place
                    clearOtherHalf(c, have, wx, wy, wz);
                }
            }

            if (kind == TreeShapes.DECOR && dy <= 1) {
                // a ground plant needs something to stand on, and its layers
                // matched to a layer block under it
                BlockState below = c.world.getBlockState(m.set(wx, wy - 1, wz));
                if (below.isAir() || !below.getFluidState().isEmpty()) continue;
                if (shape.groundLayers[i] && c.ground.group(c.world, m, below) != null) {
                    want = withLayers(want, TreeGround.layersOf(below));
                }
                m.set(wx, wy, wz);
            }
            c.world.setBlockState(m, want, FLAGS);
        }
    }

    /** The road marker's y in a column near the object, or MIN_VALUE if no road. */
    private static int roadY(Ctx c, Long2IntOpenHashMap cache, int x, int y0, int z, BlockPos.Mutable m) {
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        int got = cache.get(key);
        if (got != Integer.MIN_VALUE + 1) return got;
        int found = Integer.MIN_VALUE;
        for (int y = y0 + 8; y >= y0 - 6; y--) {
            if (RoadMarker.is(c.world.getBlockState(m.set(x, y, z)))) {
                found = y;
                break;
            }
        }
        cache.put(key, found);
        return found;
    }

    /**
     * The ground cover on a terrain block the object is replacing: a plant set to
     * that layer's height would be left standing on, or sunk into, wood or leaves.
     */
    private static void clearCover(Ctx c, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState s = c.world.getBlockState(pos);
        if (!c.ground.cover(s)) return;
        clearOtherHalf(c, s, x, y, z);
        c.world.setBlockState(pos, Blocks.AIR.getDefaultState(), FLAGS);
    }

    /**
     * A two-block plant ({@code half=lower|upper}, Conquest's or vanilla's) losing
     * one half loses the other too, rather than leaving a stalk or a floating top.
     * Stairs' {@code half=top|bottom} is not that, and is left alone.
     */
    private static void clearOtherHalf(Ctx c, BlockState have, int x, int y, int z) {
        Property<?> p = have.getBlock().getStateManager().getProperty("half");
        if (p == null) return;
        String half = String.valueOf(have.get(p));
        int oy;
        if (half.equals("lower")) oy = y + 1;
        else if (half.equals("upper")) oy = y - 1;
        else return;
        BlockPos other = new BlockPos(x, oy, z);
        if (c.writable(x, z) && c.world.getBlockState(other).isOf(have.getBlock())) {
            c.world.setBlockState(other, Blocks.AIR.getDefaultState(), FLAGS);
        }
    }

    private static BlockState withLayers(BlockState s, int k) {
        Property<?> p = s.getBlock().getStateManager().getProperty("layers");
        if (p instanceof IntProperty ip && ip.getValues().contains(k)) {
            return s.with(ip, k);
        }
        return s;
    }

    private static BlockState withNamed(BlockState s, String name, String value) {
        Property<?> p = s.getBlock().getStateManager().getProperty(name);
        return p == null ? s : withParsed(s, p, value);
    }

    private static <T extends Comparable<T>> BlockState withParsed(BlockState s, Property<T> p, String value) {
        return p.parse(value).map(v -> s.with(p, v)).orElse(s);
    }

    /**
     * Water within {@code radius} blocks: rings every 2 blocks, 12 directions
     * each. {@code sea}: only water whose surface is at sea level.
     */
    private static boolean near(Ctx c, int x, int z, int radius, boolean sea) {
        for (int r = 2; r <= radius; r += 2) {
            int dirs = Math.max(8, Math.min(24, r * 2));
            for (int i = 0; i < dirs; i++) {
                double ang = (Math.PI * 2 * i) / dirs;
                int sx = x + (int) Math.round(Math.cos(ang) * r);
                int sz = z + (int) Math.round(Math.sin(ang) * r);
                if (!c.readable(sx, sz)) continue;
                TreeGround.Column g = c.ground.column(c.world, sx, sz);
                if (g != null && g.underwater() && (!sea || g.waterTop() == c.seaLevel - 1)) return true;
            }
        }
        return false;
    }

    /** Leaves overhead, within a tall tree's height. */
    private static boolean underCanopy(Ctx c, int x, int y0, int z) {
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int y = y0 + 2; y <= Math.min(c.top - 1, y0 + 30); y++) {
            if (TreeShapes.kindOf(c.world.getBlockState(m.set(x, y, z))) == TreeShapes.LEAF) return true;
        }
        return false;
    }
}
