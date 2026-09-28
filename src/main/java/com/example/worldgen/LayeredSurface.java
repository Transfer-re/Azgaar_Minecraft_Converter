package com.example.worldgen;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.fmg.FMGRiverNetwork;
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
 * sub-biomes and slope / shore / altitude gradients.
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
                   int seaLevel, int minY, int maxY, double altTop, int seed) {
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

        Resolved(SurfaceSet set) {
            int n = set.materialCount();
            layer = new BlockState[n][9];
            full = new BlockState[n];
            layered = new boolean[n];
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
            }
            this.missing = miss;
        }

        BlockState soil(String id) {
            return soils.computeIfAbsent(id, raw -> {
                BlockState s = state(raw);
                return s != null ? s : Blocks.COARSE_DIRT.getDefaultState();
            });
        }
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

                SurfaceSet.Palette p = set.forBiome(ctx.biomes().at(x, z));
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

                int[] yk = SurfaceSet.layerOf(s);
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
            }
        }
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
