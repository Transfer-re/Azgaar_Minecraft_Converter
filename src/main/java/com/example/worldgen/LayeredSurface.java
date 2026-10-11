package com.example.worldgen;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.fmg.FMGRiverNetwork;
import com.example.surface.SurfaceCover;
import com.example.surface.SurfaceSet;
import com.example.surface.SurfaceVars;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

/**
 * Builds a chunk's ground from a {@link SurfaceSet}: the top of every land column
 * becomes a layer block ({@code layers=1..8}) sitting at the terrain's continuous
 * height, over soil, over the biome's stone -- so slopes read as smooth ramps
 * instead of full-block stairs, and the material follows the palette's
 * sub-biomes and slope / shore / altitude gradients. Then the palette's ground
 * cover ({@link SurfaceCover}): patches of another ground material, and one
 * plant or prop on top, its {@code layers} matched to the layer it stands on.
 *
 * <p>Runs after the vanilla surface rules (which still own bedrock and the
 * deepslate transition) and before rivers and roads. Road, bridge and river
 * columns are left alone: roads stay full-block paths, and river beds are
 * placed by {@code applyRivers} using {@link #bedBlock}.
 *
 * <p>The palette's blocks are resolved once per set. A block that is not
 * registered (a Conquest world opened without Conquest) falls back to a vanilla
 * stand-in without layers, so the world still generates -- blockier, but whole.
 */
final class LayeredSurface {

    private static final Logger LOGGER = LoggerFactory.getLogger("FmgLayeredSurface");
    private static final int R = SurfaceVars.SHORE_RADIUS;
    private static final int N = 16 + 2 * R;
    private static final int MAX_FILL = 48;

    /** What the surface pass needs to know about a column, from the generator's cache. */
    interface Columns {
        /** First air Y above the terrain; the top solid block is at {@code height - 1}. */
        int height(int x, int z);

        /** Continuous surface height, within one block of {@link #height}. */
        double exact(int x, int z);

        /** Road, bridge or river column: keep what the generator builds there. */
        boolean skip(int x, int z);

        /** River or lake water surface Y, or -1. */
        int freshWaterY(int x, int z);
    }

    interface BiomeIds {
        /** The land biome id whose palette this column uses (warped like the biome source). */
        String at(int x, int z);
    }

    interface BaseHeight {
        /** Cheap continuous terrain height without roads or rivers, for the shore search outside the chunk. */
        double at(int x, int z);
    }

    record Context(SurfaceSet set, Columns cols, BiomeIds biomes, BaseHeight base,
                   List<FMGRiverNetwork.RiverSample> rivers, com.example.terrain.TerrainRaster terrain,
                   int seaLevel, int minY, int maxY, double altTop, int seed,
                   double blend) {
    }

    // ---- block resolution -----------------------------------------------------------

    private static final Map<SurfaceSet, Resolved> RESOLVED = new ConcurrentHashMap<>();

    /** Resolved states for one set: layer states per material and layer count, full states, soils. */
    static final class Resolved {
        final BlockState[][] layer;      // [material][k], k = 1..8
        final BlockState[] full;
        final boolean[] layered;         // false: no layers property (stand-in) -> full blocks only
        final Map<String, BlockState> soils = new ConcurrentHashMap<>();
        final int missing;
        /** Every block the palettes lay as ground (layer or full), and every ground-cover block. */
        final java.util.Set<Block> groundBlocks = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final java.util.Set<Block> coverBlocks = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        Resolved(SurfaceSet set) {
            int n = set.materialCount();
            layer = new BlockState[n][9];
            full = new BlockState[n];
            layered = new boolean[n];
            for (String id : set.coverIds()) {
                BlockState s = state(id);
                if (s != null) coverBlocks.add(s.getBlock());
            }
            int miss = 0;
            for (int m = 0; m < n; m++) {
                BlockState fullState = state(set.fullIds[m]);
                BlockState layerBase = state(set.layerIds[m]);
                if (fullState == null) {
                    fullState = standIn(set.materialNames[m], set.groups[m]);
                    miss++;
                }
                full[m] = fullState;
                Property<?> layers = layerBase == null ? null
                        : layerBase.getBlock().getStateManager().getProperty("layers");
                layered[m] = layers != null;
                for (int k = 1; k <= 8; k++) {
                    if (layers == null) {
                        layer[m][k] = fullState;
                        continue;
                    }
                    BlockState s = with(layerBase, "layers", Integer.toString(k));
                    s = with(s, "type", "bottom");
                    s = with(s, "waterlogged", "false");
                    layer[m][k] = s;
                }
                if (layerBase == null) miss++;
                groundBlocks.add(fullState.getBlock());
                groundBlocks.add(layer[m][8].getBlock());
            }
            this.missing = miss;
        }

        BlockState soil(String id) {
            return soils.computeIfAbsent(id, raw -> {
                BlockState s = state(raw);
                return s != null ? s : Blocks.COARSE_DIRT.getDefaultState();
            });
        }

        /** A ground-cover block's default state, or null if it is not registered (no stand-ins for plants). */
        BlockState cover(String id) {
            BlockState s = covers.computeIfAbsent(id, raw -> {
                BlockState found = state(raw);
                return found != null ? found : Blocks.AIR.getDefaultState();
            });
            return s.isAir() ? null : s;
        }

        final Map<String, BlockState> covers = new ConcurrentHashMap<>();
    }

    static Resolved blocks(SurfaceSet set) {
        return RESOLVED.computeIfAbsent(set, s -> {
            Resolved b = new Resolved(s);
            if (b.missing > 0) {
                LOGGER.warn("Surface palettes '{}': {} block ids are not registered (is Conquest Reforged installed?)"
                        + " -- using vanilla stand-ins without layers", s.id, b.missing);
            }
            return b;
        });
    }

    private static BlockState state(String id) {
        Identifier parsed = id == null ? null : Identifier.tryParse(id);
        if (parsed == null || !Registries.BLOCK.containsId(parsed)) {
            return null;
        }
        Block block = Registries.BLOCK.get(parsed);
        return block.getDefaultState();
    }

    private static BlockState with(BlockState state, String name, String value) {
        Property<?> p = state.getBlock().getStateManager().getProperty(name);
        return p == null ? state : withParsed(state, p, value);
    }

    private static <T extends Comparable<T>> BlockState withParsed(BlockState state, Property<T> p, String value) {
        return p.parse(value).map(v -> state.with(p, v)).orElse(state);
    }

    private static BlockState standIn(String material, String group) {
        if (material.contains("snow")) return Blocks.SNOW_BLOCK.getDefaultState();
        if (material.contains("ice")) return Blocks.PACKED_ICE.getDefaultState();
        if (material.contains("red_sand") || material.contains("red_earth")) return Blocks.RED_SAND.getDefaultState();
        if (material.contains("chalk")) return Blocks.CALCITE.getDefaultState();
        return switch (group) {
            case "grass" -> Blocks.GRASS_BLOCK.getDefaultState();
            case "floor" -> Blocks.PODZOL.getDefaultState();
            case "moss" -> Blocks.MOSS_BLOCK.getDefaultState();
            case "wet" -> Blocks.MUD.getDefaultState();
            case "sand" -> Blocks.SAND.getDefaultState();
            case "gravel" -> Blocks.GRAVEL.getDefaultState();
            case "rock" -> Blocks.STONE.getDefaultState();
            default -> Blocks.COARSE_DIRT.getDefaultState();
        };
    }

    // ---- the pass ---------------------------------------------------------------------

    static void apply(Chunk chunk, Context ctx) {
        SurfaceSet set = ctx.set();
        Resolved blocks = blocks(set);
        Columns cols = ctx.cols();
        ChunkPos cp = chunk.getPos();
        int sx = cp.getStartX(), sz = cp.getStartZ();

        float[] coast = new float[256];
        float[] bank = new float[256];
        shoreDistances(ctx, sx, sz, coast, bank);
        // which biome's palette each column uses, blended across borders
        String[] biomeIds = com.example.surface.SurfaceBlend.choose(
                ctx.biomes()::at, sx, sz, 16, 16, ctx.blend(), ctx.seed());

        SurfaceSet.Vars v = new SurfaceSet.Vars();
        SurfaceSet.Choice choice = new SurfaceSet.Choice();
        BlockPos.Mutable m = new BlockPos.Mutable();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = sx + lx, z = sz + lz;
                if (cols.skip(x, z)) continue;
                int h = cols.height(x, z);
                int top = h - 1;
                if (top <= ctx.minY() + 2 || h >= ctx.maxY() - 2) continue;

                BlockState topState = chunk.getBlockState(m.set(x, top, z));
                if (!solid(topState)) continue;                 // a cave mouth or open water column
                BlockState above = chunk.getBlockState(m.set(x, h, z));
                boolean underwater = above.getFluidState().isIn(FluidTags.WATER);

                SurfaceSet.Palette p = set.forBiome(biomeIds[(lx << 4) | lz]);
                double s = SurfaceVars.clampExact(cols.exact(x, z), h);
                v.slope = SurfaceVars.slope(
                        SurfaceVars.clampExact(cols.exact(x + 1, z), cols.height(x + 1, z)),
                        SurfaceVars.clampExact(cols.exact(x - 1, z), cols.height(x - 1, z)),
                        SurfaceVars.clampExact(cols.exact(x, z + 1), cols.height(x, z + 1)),
                        SurfaceVars.clampExact(cols.exact(x, z - 1), cols.height(x, z - 1)));
                v.coast = coast[(lx << 4) | lz];
                v.bank = bank[(lx << 4) | lz];
                v.alt = SurfaceVars.altitude(s, ctx.seaLevel(), ctx.altTop());
                v.underwater = underwater;
                v.depth = underwater ? waterSurface(chunk, m, x, h, z, ctx.maxY()) - s : 0.0;

                set.decide(p, x, z, v, ctx.seed(), choice);
                int mat = choice.material;
                int minNeighbourTop = Math.min(Math.min(cols.height(x + 1, z), cols.height(x - 1, z)),
                        Math.min(cols.height(x, z + 1), cols.height(x, z - 1))) - 1;
                BlockState stone = blocks.full[p.stone];

                if (underwater) {
                    // beds stay full blocks: a partial layer under water would
                    // leave a dry gap in its own cell
                    int d = Math.max(1, SurfaceSet.fillDepth(p, v.slope));
                    fill(chunk, m, x, z, top, top - d + 1, blocks.full[mat]);
                    fill(chunk, m, x, z, top - d, Math.max(top - d - 3, ctx.minY() + 1), stone);
                    continue;
                }

                // a patch of the palette's ground cover (pebbles, burnt soil, a
                // boulder field) replaces the sub-biome's ground, not a ramp's
                SurfaceCover cover = p.cover;
                int raise = 0;
                if (cover != null && choice.var == SurfaceSet.VAR_SUB) {
                    int patch = cover.patch(x, z, ctx.seed());
                    if (patch >= 0) {
                        mat = cover.patchMaterials[patch];
                        raise = cover.raise(patch, x, z, ctx.seed());
                    }
                }

                int[] yk = SurfaceSet.layerOf(s + raise / 8.0);
                int yTop = yk[0], k = yk[1];
                if (!blocks.layered[mat]) {
                    // stand-in without layers: round to whole blocks
                    yTop = k >= 5 ? yTop : yTop - 1;
                    k = 8;
                }
                if (yTop >= h) {
                    if (!chunk.getBlockState(m.set(x, yTop, z)).isAir()) continue;
                } else if (yTop < top) {
                    chunk.setBlockState(m.set(x, top, z), Blocks.AIR.getDefaultState(), false);
                }
                chunk.setBlockState(m.set(x, yTop, z), blocks.layer[mat][k], false);

                int d = SurfaceSet.fillDepth(p, v.slope);
                BlockState under;
                int underDepth;
                if (set.skin[mat]) {
                    under = blocks.soil(p.soil);
                    underDepth = d;
                } else {
                    under = blocks.full[mat];
                    underDepth = Math.max(1, d);
                }
                int bottom = Math.min(yTop - 1 - underDepth - 4, minNeighbourTop - 3);
                bottom = Math.max(bottom, Math.max(yTop - MAX_FILL, ctx.minY() + 1));
                fill(chunk, m, x, z, yTop - 1, yTop - underDepth, under);
                fill(chunk, m, x, z, yTop - underDepth - 1, bottom, stone);

                if (cover != null && yTop + 3 < ctx.maxY()) {
                    placeCover(chunk, m, blocks, cover, set.groups[mat], v.slope, x, z, yTop, k, ctx.seed());
                }
            }
        }
    }

    /**
     * One plant or prop on a finished column whose layer ({@code k} eighths) is at
     * {@code yTop}. Props are rarer, so they are rolled first.
     */
    private static void placeCover(Chunk chunk, BlockPos.Mutable m, Resolved blocks, SurfaceCover cover,
                                   String group, double slope, int x, int z, int yTop, int k, int seed) {
        SurfaceCover.Scatter from = cover.props;
        int i = cover.prop(x, z, group, slope, seed);
        if (i < 0) {
            from = cover.plants;
            i = cover.plant(x, z, group, slope, seed);
        }
        if (i < 0) return;
        BlockState base = blocks.cover(from.blocks[i]);
        if (base == null) return;
        long hash = com.example.surface.SurfaceNoise.h32(x, z, seed + 0x6B1);
        switch (from.kinds[i]) {
            case SurfaceCover.PLANT -> {
                if (air(chunk, m, x, yTop + 1, z)) {
                    chunk.setBlockState(m.set(x, yTop + 1, z), dress(base, k, hash), false);
                }
            }
            case SurfaceCover.TALL -> {
                if (air(chunk, m, x, yTop + 1, z) && air(chunk, m, x, yTop + 2, z)) {
                    BlockState lower = dress(base, k, hash);
                    chunk.setBlockState(m.set(x, yTop + 1, z), with(lower, "half", "lower"), false);
                    chunk.setBlockState(m.set(x, yTop + 2, z), with(lower, "half", "upper"), false);
                }
            }
            case SurfaceCover.SUNK -> chunk.setBlockState(m.set(x, yTop, z), dress(base, 8, hash), false);
            case SurfaceCover.LOOSE -> {
                if (k == 8 && air(chunk, m, x, yTop + 1, z)) {
                    chunk.setBlockState(m.set(x, yTop + 1, z), dress(base, 8, hash), false);
                } else if (k <= 2) {
                    chunk.setBlockState(m.set(x, yTop, z), dress(base, 8, hash), false);
                }
            }
            default -> {
            }
        }
    }

    /** Most air under a carved-under layer that is filled back with soil rather than opened up. */
    private static final int GRAZE = 2;

    /**
     * After the carvers, which run after this pass and cannot cut Conquest's layer
     * blocks (they are not carver-replaceable): a cave or canyon that comes up
     * under the surface leaves the layer, and the plant on it, hanging over the
     * hole. A graze -- {@link #GRAZE} blocks of air or less over solid ground -- is
     * filled back with the palette's soil. Over a real opening the layer and what
     * stands on it go, and the hole is a cave mouth like any other; under water
     * the bed stays, so the water does not drain into the cave.
     */
    static void afterCarve(Chunk chunk, Context ctx) {
        Resolved blocks = blocks(ctx.set());
        Columns cols = ctx.cols();
        ChunkPos cp = chunk.getPos();
        BlockPos.Mutable m = new BlockPos.Mutable();
        BlockState air = Blocks.AIR.getDefaultState();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = cp.getStartX() + lx, z = cp.getStartZ() + lz;
                if (cols.skip(x, z)) continue;
                int h = cols.height(x, z);
                // the layer is at h - 1, or up to h + 1 where a patch raises it
                int yTop = Integer.MIN_VALUE;
                for (int y = Math.min(h + 1, ctx.maxY() - 3); y >= h - 1; y--) {
                    if (blocks.groundBlocks.contains(chunk.getBlockState(m.set(x, y, z)).getBlock())) {
                        yTop = y;
                        break;
                    }
                }
                if (yTop <= ctx.minY() + GRAZE + 1 || !chunk.getBlockState(m.set(x, yTop - 1, z)).isAir()) {
                    continue;
                }
                int gap = 1;
                while (gap <= GRAZE && chunk.getBlockState(m.set(x, yTop - 1 - gap, z)).isAir()) gap++;
                if (gap <= GRAZE && solid(chunk.getBlockState(m.set(x, yTop - 1 - gap, z)))) {
                    BlockState soil = blocks.soil(ctx.set().forBiome(ctx.biomes().at(x, z)).soil);
                    for (int y = yTop - gap; y < yTop; y++) {
                        chunk.setBlockState(m.set(x, y, z), soil, false);
                    }
                    continue;
                }
                if (!chunk.getBlockState(m.set(x, yTop + 1, z)).getFluidState().isEmpty()) continue;
                for (int y = yTop + 2; y > yTop; y--) {
                    if (blocks.coverBlocks.contains(chunk.getBlockState(m.set(x, y, z)).getBlock())) {
                        chunk.setBlockState(m, air, false);
                    }
                }
                chunk.setBlockState(m.set(x, yTop, z), air, false);
            }
        }
    }

    /**
     * A cover block's state for a column: {@code layers} from the layer under it
     * (what Conquest's own placement does, so the model sinks onto the ground),
     * dry, lying flat (lichens on the down face, rocks not on an edge), unstacked,
     * leaves persistent, and growth stage / model variant / rock density drawn
     * from the column's hash.
     */
    private static BlockState dress(BlockState base, int k, long hash) {
        BlockState s = base;
        int draw = 0;
        for (Property<?> p : base.getProperties()) {
            switch (p.getName()) {
                case "layers" -> s = with(s, p.getName(), Integer.toString(k));
                case "waterlogged", "edge", "north", "east", "south", "west", "up" -> s = with(s, p.getName(), "false");
                case "down", "persistent" -> s = with(s, p.getName(), "true");
                case "half" -> s = with(s, p.getName(), "lower");
                case "height" -> s = nth(s, p, 0);
                case "density" -> {
                    // mostly a few stones, now and then a heap
                    double r = ((hash >>> 24) & 0xFF) / 256.0;
                    s = nth(s, p, r < 0.45 ? 0 : r < 0.75 ? 1 : r < 0.92 ? 2 : 3);
                }
                case "age", "random" -> s = nth(s, p, (int) ((hash >>> (8 * draw++)) & 0xFF));
                default -> {
                }
            }
        }
        return s;
    }

    /** The property's {@code i}-th value (wrapping), in its natural order. */
    private static <T extends Comparable<T>> BlockState nth(BlockState s, Property<T> p, int i) {
        java.util.Collection<T> values = p.getValues();
        int want = Math.floorMod(i, values.size());
        for (T v : values) {
            if (want-- == 0) return s.with(p, v);
        }
        return s;
    }

    private static boolean air(Chunk chunk, BlockPos.Mutable m, int x, int y, int z) {
        return chunk.getBlockState(m.set(x, y, z)).isAir();
    }

    /** Material for a river or lake bed block, by water depth. */
    static BlockState bedBlock(Context ctx, int x, int z, double depth) {
        SurfaceSet set = ctx.set();
        SurfaceSet.Palette p = set.forBiome(ctx.biomes().at(x, z));
        SurfaceSet.Vars v = new SurfaceSet.Vars();
        v.underwater = true;
        v.depth = depth;
        SurfaceSet.Choice c = new SurfaceSet.Choice();
        set.decide(p, x, z, v, ctx.seed(), c);
        return blocks(set).full[c.material];
    }

    // ---- helpers ---------------------------------------------------------------------

    private static boolean solid(BlockState s) {
        return !s.isAir() && s.getFluidState().isEmpty() && !s.isOf(Blocks.BEDROCK);
    }

    /** Replace solid blocks from yHigh down to yLow (inclusive); air and fluid (caves) are kept. */
    private static void fill(Chunk chunk, BlockPos.Mutable m, int x, int z, int yHigh, int yLow, BlockState state) {
        for (int y = yHigh; y >= yLow; y--) {
            if (solid(chunk.getBlockState(m.set(x, y, z)))) {
                chunk.setBlockState(m, state, false);
            }
        }
    }

    private static double waterSurface(Chunk chunk, BlockPos.Mutable m, int x, int h, int z, int maxY) {
        int y = h;
        while (y < maxY - 1 && chunk.getBlockState(m.set(x, y + 1, z)).getFluidState().isIn(FluidTags.WATER)) {
            y++;
        }
        return y + 1.0;
    }

    /**
     * Horizontal distance (blocks, chamfer metric) from each column of the chunk
     * to the nearest sea water and to the nearest river/lake water, searched
     * {@link #R} blocks past the chunk edge. Farther than that reads as infinite.
     */
    private static void shoreDistances(Context ctx, int sx, int sz, float[] coast, float[] bank) {
        byte[] kind = new byte[N * N];
        for (int px = 0; px < N; px++) {
            for (int pz = 0; pz < N; pz++) {
                int x = sx - R + px, z = sz - R + pz;
                boolean inside = px >= R && px < R + 16 && pz >= R && pz < R + 16;
                byte k = SurfaceVars.LAND;
                if (inside) {
                    if (ctx.cols().freshWaterY(x, z) >= 0) k = SurfaceVars.FRESH;
                    else if (ctx.cols().height(x, z) < ctx.seaLevel()) k = SurfaceVars.SEA;
                } else {
                    double base = ctx.base().at(x, z);
                    if (isRiver(ctx.rivers(), x, z) || isLake(ctx.terrain(), x, z, base)) k = SurfaceVars.FRESH;
                    else if (base < ctx.seaLevel() - 0.5) k = SurfaceVars.SEA;
                }
                kind[px * N + pz] = k;
            }
        }
        float[] dSea = SurfaceVars.chamfer(kind, N, SurfaceVars.SEA);
        float[] dFresh = SurfaceVars.chamfer(kind, N, SurfaceVars.FRESH);
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int i = (lx + R) * N + (lz + R);
                coast[(lx << 4) | lz] = SurfaceVars.capped(dSea[i]);
                bank[(lx << 4) | lz] = SurfaceVars.capped(dFresh[i]);
            }
        }
    }

    private static boolean isRiver(List<FMGRiverNetwork.RiverSample> rivers, int x, int z) {
        for (FMGRiverNetwork.RiverSample s : rivers) {
            double dx = x - s.x, dz = z - s.z;
            double r = s.halfWidth + 0.5;
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    private static boolean isLake(com.example.terrain.TerrainRaster terrain, int x, int z, double base) {
        if (terrain == null) return false;
        int lake = terrain.lakeWaterAt(x, z);
        return lake != com.example.terrain.TerrainRaster.NO_WATER && base < lake;
    }
}
