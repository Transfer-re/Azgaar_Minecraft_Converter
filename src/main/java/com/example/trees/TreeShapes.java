package com.example.trees;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.mixin.StructureTemplateAccessor;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Property;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

/**
 * The tree objects in the compact form the placer pastes from, loaded once per
 * template through the vanilla {@link StructureTemplateManager} (so datapack
 * overrides and reloads work as for any structure).
 *
 * <p>Blocks are kept relative to the object's paste point and sorted bottom-up,
 * so anything that rests on something -- a plant on the ground, a mushroom on a
 * log -- is pasted after what it rests on. Every block state is rotated for all
 * four turns up front.
 *
 * <p>Rotation is by property name, the way WorldEdit does it, which is what the
 * TreeGenerator handoff vetted ("WorldEdit rotates facing, axis and stairs
 * properties, which is all the direction these pieces have"). It does not go
 * through {@code BlockState.rotate}: that depends on each Conquest block class
 * overriding {@code rotate}, and a class that forgot would leave a branch
 * pointing the wrong way. The survey of all 6,221 states in the tree set found
 * only {@code facing} (horizontal) and {@code axis} carrying direction;
 * north/east/south/west booleans and a 0-15 {@code rotation} are handled too, in
 * case regenerated trees bring them in.
 */
final class TreeShapes {

    private static final Logger LOGGER = LoggerFactory.getLogger("FmgTrees");

    static final byte WOOD = 0, LEAF = 1, DECOR = 2, SOIL = 3;

    /** One object, ready to paste. */
    static final class Shape {
        final short[] dx, dy, dz;
        final byte[] kind;
        /** states[r][i]: block i turned r quarter-turns clockwise. */
        final BlockState[][] states;
        /** Ground plants and rocks: their own {@code layers} must match the layer they stand on. */
        final boolean[] groundLayers;
        final int minY, maxY;
        /** Unrotated horizontal extent, relative to the paste point. */
        final int minX, maxX, minZ, maxZ;

        Shape(List<Block> blocks) {
            int n = blocks.size();
            dx = new short[n];
            dy = new short[n];
            dz = new short[n];
            kind = new byte[n];
            groundLayers = new boolean[n];
            states = new BlockState[4][n];
            int y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                Block b = blocks.get(i);
                dx[i] = (short) b.x;
                dy[i] = (short) b.y;
                dz[i] = (short) b.z;
                kind[i] = kindOf(b.state);
                groundLayers[i] = b.state.getBlock().getStateManager().getProperty("layers") instanceof IntProperty
                        && b.state.getBlock().getStateManager().getProperty("type") == null;
                for (int r = 0; r < 4; r++) {
                    states[r][i] = rotate(b.state, r);
                }
                y0 = Math.min(y0, b.y);
                y1 = Math.max(y1, b.y);
                x0 = Math.min(x0, b.x);
                x1 = Math.max(x1, b.x);
                z0 = Math.min(z0, b.z);
                z1 = Math.max(z1, b.z);
            }
            minY = y0;
            maxY = y1;
            minX = x0;
            maxX = x1;
            minZ = z0;
            maxZ = z1;
        }

        int size() {
            return dx.length;
        }
    }

    private record Block(int x, int y, int z, BlockState state) {
    }

    private static final Map<Identifier, Optional<Shape>> CACHE = new ConcurrentHashMap<>();
    private static final Set<Identifier> MISSING = ConcurrentHashMap.newKeySet();

    private TreeShapes() {
    }

    /** The object for {@code template}, or empty if the template does not exist. */
    static Optional<Shape> get(StructureTemplateManager manager, Identifier template, int ax, int ay, int az) {
        Optional<Shape> cached = CACHE.get(template);
        if (cached != null) {
            return cached;
        }
        Optional<Shape> loaded = load(manager, template, ax, ay, az);
        Optional<Shape> prev = CACHE.putIfAbsent(template, loaded);
        return prev != null ? prev : loaded;
    }

    /** Forget every loaded object (datapack reload, server stop). */
    static void clear() {
        CACHE.clear();
        MISSING.clear();
    }

    private static Optional<Shape> load(StructureTemplateManager manager, Identifier id, int ax, int ay, int az) {
        Optional<StructureTemplate> template = manager.getTemplate(id);
        if (template.isEmpty()) {
            if (MISSING.add(id) && MISSING.size() <= 5) {
                LOGGER.warn("Tree template {} not found -- run `python -m fmgtrees.treegen` to generate the"
                        + " structure files (further misses are not logged)", id);
            }
            return Optional.empty();
        }
        List<StructureTemplate.PalettedBlockInfoList> lists =
                ((StructureTemplateAccessor) template.get()).fmg$blockInfoLists();
        if (lists.isEmpty()) {
            return Optional.empty();
        }
        List<Block> blocks = new ArrayList<>();
        for (StructureTemplate.StructureBlockInfo info : lists.get(0).getAll()) {
            BlockState s = info.state();
            if (s.isAir() || s.isOf(Blocks.STRUCTURE_VOID)) {
                continue;
            }
            blocks.add(new Block(info.pos().getX() - ax, info.pos().getY() - ay, info.pos().getZ() - az, s));
        }
        if (blocks.isEmpty()) {
            return Optional.empty();
        }
        blocks.sort(Comparator.comparingInt(Block::y));
        return Optional.of(new Shape(blocks));
    }

    // ---- block classes -------------------------------------------------------------

    /**
     * Wood, leaves, soil (a fallen log's root plate) or anything else. Tags first --
     * Conquest files its logs and branches under {@code minecraft:logs} -- then the
     * block's name, for pieces no tag covers.
     */
    static byte kindOf(BlockState s) {
        if (s.isIn(BlockTags.LEAVES)) return LEAF;
        if (s.isIn(BlockTags.LOGS)) return WOOD;
        String p = Registries.BLOCK.getId(s.getBlock()).getPath();
        if (p.contains("leaves") || p.contains("needles")) return LEAF;
        if (p.contains("log") || p.contains("wood") || p.contains("branch") || p.contains("bark")
                || p.contains("stump") || p.contains("trunk") || p.contains("pillar")) {
            return WOOD;
        }
        if (s.isIn(BlockTags.DIRT)) return SOIL;
        return DECOR;
    }

    // ---- rotation --------------------------------------------------------------------

    private static final BlockRotation[] TURNS = {
            BlockRotation.NONE, BlockRotation.CLOCKWISE_90, BlockRotation.CLOCKWISE_180,
            BlockRotation.COUNTERCLOCKWISE_90};
    private static final String[] SIDES = {"north", "east", "south", "west"};

    /** Rotate a position: r quarter-turns clockwise seen from above (north -> east). */
    static int rx(int x, int z, int r) {
        return switch (r & 3) {
            case 1 -> -z;
            case 2 -> -x;
            case 3 -> z;
            default -> x;
        };
    }

    static int rz(int x, int z, int r) {
        return switch (r & 3) {
            case 1 -> x;
            case 2 -> -z;
            case 3 -> -x;
            default -> z;
        };
    }

    static BlockState rotate(BlockState state, int r) {
        if ((r & 3) == 0) {
            return state;
        }
        BlockRotation turn = TURNS[r & 3];
        BlockState out = state;
        boolean sides = false;
        for (Property<?> p : state.getProperties()) {
            Object v = state.get(p);
            if (v instanceof Direction d && d.getAxis().isHorizontal()) {
                out = with(out, p, turn.rotate(d));
            } else if (v instanceof Direction.Axis a && a != Direction.Axis.Y && (r & 1) == 1) {
                out = with(out, p, a == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
            } else if (p instanceof IntProperty ip && p.getName().equals("rotation")
                    && ip.getValues().size() == 16) {
                out = with(out, p, Math.floorMod((Integer) v + 4 * r, 16));
            } else if (p instanceof BooleanProperty && isSide(p.getName())) {
                sides = true;
            }
        }
        if (sides) {
            // connection booleans: the value on each side moves to the side it turns to
            for (int s = 0; s < 4; s++) {
                Property<?> from = state.getBlock().getStateManager().getProperty(SIDES[s]);
                Property<?> to = state.getBlock().getStateManager().getProperty(SIDES[(s + r) & 3]);
                if (from instanceof BooleanProperty && to instanceof BooleanProperty) {
                    out = with(out, to, state.get(from));
                }
            }
        }
        return out;
    }

    private static boolean isSide(String name) {
        for (String s : SIDES) {
            if (s.equals(name)) return true;
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState with(BlockState state, Property p, Object value) {
        return p.getValues().contains(value) ? state.with(p, (Comparable) value) : state;
    }
}
