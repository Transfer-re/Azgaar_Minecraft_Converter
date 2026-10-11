package com.example.trees;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import com.example.surface.SurfaceSet;
import com.example.worldgen.RoadMarker;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.StructureWorldAccess;

/**
 * What the ground is at a column, read from the world as it stands.
 *
 * <p>Ground is classified into the surface palettes' own groups -- grass, floor,
 * soil, moss, wet, sand, gravel, rock, ice -- straight from the palette file's
 * material list, so the layer and full block of every material the layered
 * surface can lay down map to exactly the group {@code palettes.py} gave it.
 * Vanilla blocks (a world without the palettes, a root plate's dirt) fall back
 * to their tags, then to the block's name.
 *
 * <p>The scan walks down from the world-surface heightmap past everything that
 * is not ground: other objects' wood and leaves, ground-cover plants and rocks,
 * snow, the invisible road marker, and water (whose top it records, for the
 * mangroves and for "near water").
 */
final class TreeGround {

    static final String WATER = "water";

    /** One column, as found. {@code yTop} is the ground's top block, {@code layers} its eighths. */
    record Column(int yTop, String group, int layers, int waterTop, boolean road) {
        int waterDepth() {
            return waterTop < 0 ? 0 : waterTop - yTop;
        }

        boolean underwater() {
            return waterTop >= 0;
        }
    }

    private static final Map<SurfaceSet, Map<Block, String>> GROUPS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<SurfaceSet, Set<Block>> COVERS = new java.util.concurrent.ConcurrentHashMap<>();

    private final Map<Block, String> materials;
    private final Set<Block> cover;
    private final int bottom;
    private final BlockPos.Mutable m = new BlockPos.Mutable();

    TreeGround(SurfaceSet set, int bottom) {
        this.materials = set == null ? Map.of() : GROUPS.computeIfAbsent(set, TreeGround::materialGroups);
        this.cover = set == null ? Set.of() : COVERS.computeIfAbsent(set, TreeGround::coverBlocks);
        this.bottom = bottom;
    }

    private static Set<Block> coverBlocks(SurfaceSet set) {
        Set<Block> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String id : set.coverIds()) {
            Identifier parsed = Identifier.tryParse(id);
            if (parsed != null && Registries.BLOCK.containsId(parsed)) {
                out.add(Registries.BLOCK.get(parsed));
            }
        }
        return out;
    }

    /** Whether a block is one the layered surface scatters on the ground (a plant, rock or cone). */
    boolean cover(BlockState s) {
        return cover.contains(s.getBlock());
    }

    private static Map<Block, String> materialGroups(SurfaceSet set) {
        Map<Block, String> out = new IdentityHashMap<>();
        for (int i = 0; i < set.groups.length; i++) {
            put(out, set.layerIds[i], set.groups[i]);
            put(out, set.fullIds[i], set.groups[i]);
        }
        return out;
    }

    private static void put(Map<Block, String> out, String id, String group) {
        Identifier parsed = id == null ? null : Identifier.tryParse(id);
        if (parsed != null && Registries.BLOCK.containsId(parsed)) {
            out.putIfAbsent(Registries.BLOCK.get(parsed), group);
        }
    }

    static void clear() {
        GROUPS.clear();
        COVERS.clear();
    }

    /** The ground group of a block, or null if it is not ground. */
    String group(StructureWorldAccess world, BlockPos pos, BlockState s) {
        String g = materials.get(s.getBlock());
        if (g != null) return g;
        if (s.isAir() || RoadMarker.is(s)) return null;
        byte kind = TreeShapes.kindOf(s);
        if (kind == TreeShapes.WOOD || kind == TreeShapes.LEAF) return null;
        if (s.isOf(Blocks.SNOW)) return null;                       // snow layer on top of the ground
        if (s.isIn(BlockTags.SAND)) return "sand";
        if (s.isOf(Blocks.GRAVEL) || s.isOf(Blocks.SUSPICIOUS_GRAVEL)) return "gravel";
        if (s.isOf(Blocks.MUD) || s.isOf(Blocks.CLAY) || s.isOf(Blocks.MUDDY_MANGROVE_ROOTS)) return "wet";
        if (s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.MYCELIUM)) return "grass";
        if (s.isOf(Blocks.PODZOL)) return "floor";
        if (s.isOf(Blocks.MOSS_BLOCK)) return "moss";
        if (s.isIn(BlockTags.DIRT)) return "soil";
        if (s.isIn(BlockTags.ICE) || s.isOf(Blocks.SNOW_BLOCK) || s.isOf(Blocks.POWDER_SNOW)) return "ice";
        if (s.isIn(BlockTags.BASE_STONE_OVERWORLD)) return "rock";
        if (s.getCollisionShape(world, pos).isEmpty()) return null;  // plants, props without collision
        if (!s.isFullCube(world, pos)) return null;                  // rocks and other props on top
        String p = Registries.BLOCK.getId(s.getBlock()).getPath();
        if (p.contains("mud") || p.contains("silt") || p.contains("peat") || p.contains("clay")) return "wet";
        if (p.contains("sand")) return "sand";
        if (p.contains("gravel") || p.contains("pebble") || p.contains("stones")) return "gravel";
        if (p.contains("dirt") || p.contains("soil") || p.contains("loam") || p.contains("earth")) return "soil";
        if (p.contains("snow") || p.contains("ice")) return "ice";
        return "rock";
    }

    /**
     * The ground at a column: its top block, group and layers, the water over it
     * (if any), and whether a road runs here. Null if nothing within reach.
     */
    Column column(StructureWorldAccess world, int x, int z) {
        int y = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
        int waterTop = -1;
        boolean road = false;
        for (int steps = 0; y > bottom && steps < 128; y--, steps++) {
            BlockState s = world.getBlockState(m.set(x, y, z));
            if (s.isAir()) continue;
            if (RoadMarker.is(s)) {
                road = true;
                continue;
            }
            String g = group(world, m, s);
            if (g != null) {
                return new Column(y, g, layersOf(s), waterTop, road);
            }
            FluidState f = s.getFluidState();
            if (!f.isEmpty()) {
                if (!f.isIn(FluidTags.WATER)) {
                    return new Column(y, "lava", 8, -1, road);
                }
                if (waterTop < 0) waterTop = y;
            }
        }
        return null;
    }

    /** The eighths a layer block fills; 8 for anything else. */
    static int layersOf(BlockState s) {
        Property<?> p = s.getBlock().getStateManager().getProperty("layers");
        if (p instanceof IntProperty ip) {
            int v = s.get(ip);
            return v >= 1 && v <= 8 ? v : 8;
        }
        return 8;
    }
}
