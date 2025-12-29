package com.example.worldgen;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.worldmap.MapInfo;
import com.example.worldmap.runtime.MapImageCache;
import com.example.worldmap.runtime.MapImageData;
import com.example.fmg.FMGRouteSampler;
import com.example.fmg.FMRiverSampler;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryElementCodec;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.gen.noise.NoiseRouter;

import com.example.mixin.NoiseConfigAccessor;

/**
 * Chunk generator that projects block heights from FMG exports instead of vanilla noise.
 */
public final class ImageMapChunkGenerator extends ChunkGenerator {

    public static final MapCodec<ImageMapChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
                        RegistryElementCodec.of(
                            RegistryKeys.CHUNK_GENERATOR_SETTINGS,
                            ChunkGeneratorSettings.CODEC
                        )
                        .fieldOf("settings")
                        .forGetter(generator -> generator.delegateSettings),
                        MapInfo.CODEC
                        .fieldOf("map")
                        .forGetter(ImageMapChunkGenerator::mapInfo)
            ).apply(instance, ImageMapChunkGenerator::new)
    );

    private final NoiseChunkGenerator delegate;
    private final RegistryEntry<ChunkGeneratorSettings> delegateSettings;
    private final RegistryEntry<ChunkGeneratorSettings> runtimeSettings;
    private final MapInfo mapInfo;

    private final ThreadLocal<ChunkColumnCache> columnCache;

    private final DensityFunction heightCap;
    private final AtomicBoolean noiseRouterPatched = new AtomicBoolean(false);

    public ImageMapChunkGenerator(
            BiomeSource biomeSource,
            RegistryEntry<ChunkGeneratorSettings> settings,
            MapInfo mapInfo
    ) {
        super(biomeSource);
        this.delegateSettings = settings;
        this.mapInfo = mapInfo;

        this.columnCache = ThreadLocal.withInitial(() -> new ChunkColumnCache(mapInfo));

        // Build an in-memory copy of the provided settings, but replace the density
        // functions with a height-cap derived from the FMG map.
        ChunkGeneratorSettings base = settings.value();
        NoiseRouter baseRouter = base.noiseRouter();

        this.heightCap = new FmgHeightCapDensityFunction(mapInfo, this.columnCache);
        NoiseRouter fixedRouter = new NoiseRouter(
            baseRouter.barrierNoise(),
            baseRouter.fluidLevelFloodednessNoise(),
            baseRouter.fluidLevelSpreadNoise(),
            baseRouter.lavaNoise(),
            baseRouter.temperature(),
            baseRouter.vegetation(),
            baseRouter.continents(),
            baseRouter.erosion(),
            baseRouter.depth(),
            baseRouter.ridges(),
            this.heightCap,
            this.heightCap,
            baseRouter.veinToggle(),
            baseRouter.veinRidged(),
            baseRouter.veinGap()
        );

        @SuppressWarnings("deprecation")
        ChunkGeneratorSettings fixedSettings = new ChunkGeneratorSettings(
            base.generationShapeConfig(),
            base.defaultBlock(),
            base.defaultFluid(),
            fixedRouter,
            base.surfaceRule(),
            base.spawnTarget(),
            mapInfo.seaLevel(),
            base.mobGenerationDisabled(),
            false,
            false,
            base.usesLegacyRandom()
        );
        this.runtimeSettings = RegistryEntry.of(fixedSettings);
        this.delegate = new NoiseChunkGenerator(biomeSource, this.runtimeSettings);
    }

    public MapInfo mapInfo() {
        return mapInfo;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> getCodec() {
        return CODEC;
    }

    @Override
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig, BiomeAccess biomeAccess,
                      StructureAccessor structureAccessor, Chunk chunk) {
        // Disable vanilla carving so our projected heightmap stays intact.
    }

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structures, NoiseConfig noiseConfig, Chunk chunk) {
        // We handle surface placement ourselves in rewriteTerrain, so this is a no-op.
    }

    @Override
    public void populateEntities(ChunkRegion region) {
        delegate.populateEntities(region);
    }

    @Override
    public int getWorldHeight() {
        return delegate.getWorldHeight();
    }

    @Override
    public int getSeaLevel() {
        return mapInfo.seaLevel();
    }

    @Override
    public int getMinimumY() {
        return delegate.getMinimumY();
    }

    @Override
    public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView world, NoiseConfig noiseConfig) {
        return columnCache.get().getHeight(x, z);
    }

    @Override
    public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
        return delegate.getColumnSample(x, z, world, noiseConfig);
    }

    @Override
    public CompletableFuture<Chunk> populateNoise(Blender blender, NoiseConfig noiseConfig,
            StructureAccessor structureAccessor, Chunk chunk) {
        patchNoiseRouterOnce(noiseConfig);
        return delegate.populateNoise(blender, noiseConfig, structureAccessor, chunk)
                .thenApply(result -> {
                    // Keep the bulk fill inside vanilla noise generation (fast).
                    // We only post-process the surface layers and route blocks.
                    applySurfaceAndRoutes(result);
                    return result;
                });
    }

    private void patchNoiseRouterOnce(NoiseConfig noiseConfig) {
        if (!noiseRouterPatched.compareAndSet(false, true)) {
            return;
        }

        // Vanilla NoiseChunkGenerator uses noiseConfig.getNoiseRouter() during fill.
        // NoiseConfig is created/cached by the ChunkGeneratorSettings registry key.
        // We point our FMG dimension at a distinct settings key (aliasing the vanilla overworld settings)
        // so this patch only affects the FMG dimension and not the real minecraft:overworld.
        NoiseRouter base = noiseConfig.getNoiseRouter();
        NoiseRouter patched = new NoiseRouter(
                base.barrierNoise(),
                base.fluidLevelFloodednessNoise(),
                base.fluidLevelSpreadNoise(),
                base.lavaNoise(),
                base.temperature(),
                base.vegetation(),
                base.continents(),
                base.erosion(),
                base.depth(),
                base.ridges(),
                this.heightCap,
                this.heightCap,
                base.veinToggle(),
                base.veinRidged(),
                base.veinGap()
        );

        ((NoiseConfigAccessor) (Object) noiseConfig).fantasymapgenerator$setNoiseRouter(patched);
    }

    @Override
    public void appendDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
        text.add("FMG map: " + mapInfo.fmgExport());

        // Debug info to validate that our height cap is being used.
        int height = columnCache.get().getHeight(pos.getX(), pos.getZ());
        double cap = heightCap.sample(new DensityFunction.UnblendedNoisePos(pos.getX(), pos.getY(), pos.getZ()));
        text.add("FMG height@XZ: " + height + " cap@pos: " + String.format(java.util.Locale.ROOT, "%.2f", cap));
    }

    private void applySurfaceAndRoutes(Chunk chunk) {
        ChunkPos chunkPos = chunk.getPos();
        int minY = getMinimumY();
        int maxY = getWorldHeight();
        int seaLevel = mapInfo.seaLevel();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        ChunkColumnCache cache = columnCache.get();

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = chunkPos.getStartX() + localX;
                int worldZ = chunkPos.getStartZ() + localZ;
                int target = cache.getHeight(worldX, worldZ);

                if (target <= minY + 1 || target >= maxY - 1) {
                    continue;
                }

                if (target < seaLevel) {
                    int floorY = Math.min(target, seaLevel - 1);
                    if (floorY <= minY + 1) {
                        continue;
                    }

                    // Underwater floor styling (matches previous behavior).
                    mutable.set(worldX, floorY, worldZ);
                    chunk.setBlockState(mutable, Blocks.SAND.getDefaultState(), false);
                    for (int y = floorY - 1; y >= floorY - 3 && y > minY; y--) {
                        mutable.set(worldX, y, worldZ);
                        chunk.setBlockState(mutable, Blocks.SANDSTONE.getDefaultState(), false);
                    }
                    continue;
                }

                // Land surface styling (same logic as before).
                int biomeX = worldX >> 2;
                int biomeZ = worldZ >> 2;
                int biomeY = target >> 2;
                RegistryEntry<Biome> biome = chunk.getBiomeForNoiseGen(biomeX, biomeY, biomeZ);

                boolean isDesert = isBiome(biome, BiomeKeys.DESERT) || isBiome(biome, BiomeKeys.BADLANDS);
                boolean isSnowy = isBiome(biome, BiomeKeys.SNOWY_PLAINS) || isBiome(biome, BiomeKeys.SNOWY_TAIGA)
                        || isBiome(biome, BiomeKeys.SNOWY_SLOPES) || isBiome(biome, BiomeKeys.FROZEN_PEAKS);
                boolean isTaiga = isBiome(biome, BiomeKeys.TAIGA);

                mutable.set(worldX, target, worldZ);
                if (isDesert) {
                    chunk.setBlockState(mutable, Blocks.SAND.getDefaultState(), false);
                    for (int y = target - 1; y >= target - 3 && y > minY; y--) {
                        mutable.set(worldX, y, worldZ);
                        chunk.setBlockState(mutable, Blocks.SANDSTONE.getDefaultState(), false);
                    }
                } else if (isSnowy) {
                    chunk.setBlockState(mutable, Blocks.SNOW_BLOCK.getDefaultState(), false);
                    for (int y = target - 1; y >= target - 3 && y > minY; y--) {
                        mutable.set(worldX, y, worldZ);
                        chunk.setBlockState(mutable, Blocks.DIRT.getDefaultState(), false);
                    }
                } else if (isTaiga) {
                    chunk.setBlockState(mutable, Blocks.PODZOL.getDefaultState(), false);
                    for (int y = target - 1; y >= target - 3 && y > minY; y--) {
                        mutable.set(worldX, y, worldZ);
                        chunk.setBlockState(mutable, Blocks.DIRT.getDefaultState(), false);
                    }
                } else {
                    chunk.setBlockState(mutable, Blocks.GRASS_BLOCK.getDefaultState(), false);
                    for (int y = target - 1; y >= target - 3 && y > minY; y--) {
                        mutable.set(worldX, y, worldZ);
                        chunk.setBlockState(mutable, Blocks.DIRT.getDefaultState(), false);
                    }
                }

                // Overlay route blocks at the smoothed height.
                FMGRouteSampler.RouteKind routeKind = cache.getRouteKind(worldX, worldZ);
                if (routeKind != FMGRouteSampler.RouteKind.NONE) {
                    BlockState pathBlock = choosePathBlock(routeKind, isDesert, isSnowy, isTaiga, worldX, worldZ);
                    if (pathBlock != null) {
                        mutable.set(worldX, target, worldZ);
                        chunk.setBlockState(mutable, pathBlock, false);
                    }
                }
            }
        }
    }

    /**
     * Caches a fully-smoothed 16x16 height + route kind grid for the most recently
     * accessed chunk on the current thread.
     */
    private static final class ChunkColumnCache {
        private final MapInfo mapInfo;

        // LRU of a few recently used chunk grids for this thread.
        // Noise sampling during fill can touch neighbor chunks; caching prevents
        // recomputing 16x16 grids over and over.
        private final LinkedHashMap<Long, ChunkGrid> grids = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, ChunkGrid> eldest) {
                return size() > 9; // 3x3 neighborhood is usually enough
            }
        };

        ChunkColumnCache(MapInfo mapInfo) {
            this.mapInfo = mapInfo;
        }

        int getHeight(int worldX, int worldZ) {
            ChunkGrid grid = getGrid(worldX, worldZ);
            return grid.heights[idx(worldX, worldZ)];
        }

        FMGRouteSampler.RouteKind getRouteKind(int worldX, int worldZ) {
            ChunkGrid grid = getGrid(worldX, worldZ);
            return grid.routes[idx(worldX, worldZ)];
        }

        private ChunkGrid getGrid(int worldX, int worldZ) {
            int chunkX = worldX >> 4;
            int chunkZ = worldZ >> 4;
            long key = ChunkPos.toLong(chunkX, chunkZ);
            ChunkGrid existing = grids.get(key);
            if (existing != null) {
                return existing;
            }
            ChunkGrid computed = computeGrid(chunkX, chunkZ);
            grids.put(key, computed);
            return computed;
        }

        private ChunkGrid computeGrid(int chunkX, int chunkZ) {
            ChunkGrid out = new ChunkGrid();

            MapImageData data = MapImageCache.get(mapInfo);
            int seaLevel = mapInfo.seaLevel();
            int startX = chunkX << 4;
            int startZ = chunkZ << 4;

            // 1) Sample FMG height + route kind for this chunk
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    int x = startX + localX;
                    int z = startZ + localZ;
                    int index = (localX << 4) | localZ;

                    int target = data.sampleHeightY(x, z);
                    FMGRouteSampler.RouteKind route = FMGRouteSampler.sampleRoute(data.fmgData(), x, z);
                    out.routes[index] = route;

                    // River carving from your current generator.
                    FMRiverSampler.RiverQuery rq = FMRiverSampler.query(x, z);
                    if (rq.hit) {
                        double carved = FMRiverSampler.carvedHeight(rq, seaLevel);
                        int riverBed = (int) Math.floor(carved);
                        target = Math.min(target, riverBed);
                    }

                    out.heights[index] = target;
                }
            }

            // 2) Smooth route heights (only land, only along routes)
            for (int pass = 0; pass < 2; pass++) {
                for (int localX = 0; localX < 16; localX++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        int index = (localX << 4) | localZ;
                        if (out.routes[index] == FMGRouteSampler.RouteKind.NONE) {
                            continue;
                        }

                        int h = out.heights[index];
                        if (h < seaLevel) {
                            continue; // don't touch water columns
                        }

                        int minNeighbour = h;

                        // 4-neighbourhood along the route
                        if (localX > 0 && out.routes[((localX - 1) << 4) | localZ] != FMGRouteSampler.RouteKind.NONE) {
                            int nh = out.heights[((localX - 1) << 4) | localZ];
                            if (nh >= seaLevel) minNeighbour = Math.min(minNeighbour, nh);
                        }
                        if (localX < 15 && out.routes[((localX + 1) << 4) | localZ] != FMGRouteSampler.RouteKind.NONE) {
                            int nh = out.heights[((localX + 1) << 4) | localZ];
                            if (nh >= seaLevel) minNeighbour = Math.min(minNeighbour, nh);
                        }
                        if (localZ > 0 && out.routes[(localX << 4) | (localZ - 1)] != FMGRouteSampler.RouteKind.NONE) {
                            int nh = out.heights[(localX << 4) | (localZ - 1)];
                            if (nh >= seaLevel) minNeighbour = Math.min(minNeighbour, nh);
                        }
                        if (localZ < 15 && out.routes[(localX << 4) | (localZ + 1)] != FMGRouteSampler.RouteKind.NONE) {
                            int nh = out.heights[(localX << 4) | (localZ + 1)];
                            if (nh >= seaLevel) minNeighbour = Math.min(minNeighbour, nh);
                        }

                        // If this column sticks up more than 1 block above its neighbours on the route, carve it down.
                        if (h - minNeighbour > 1) {
                            int newH = Math.max(seaLevel, minNeighbour + 1);
                            out.heights[index] = newH;
                        }
                    }
                }
            }

            return out;
        }

        private static int idx(int worldX, int worldZ) {
            int localX = worldX & 15;
            int localZ = worldZ & 15;
            return (localX << 4) | localZ;
        }

        private static final class ChunkGrid {
            final int[] heights = new int[16 * 16];
            final FMGRouteSampler.RouteKind[] routes = new FMGRouteSampler.RouteKind[16 * 16];
        }
    }

    /**
     * Density function that makes everything solid below the FMG height (and air above).
     *
     * This is intentionally large-magnitude so it dominates other density functions,
     * keeping the resulting terrain tightly locked to the height map.
     */
    private static final class FmgHeightCapDensityFunction implements DensityFunction {
        private static final double SCALE = 64.0;

        private static final MapCodec<FmgHeightCapDensityFunction> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                MapInfo.CODEC.fieldOf("map").forGetter(df -> df.mapInfo)
            ).apply(instance, info -> new FmgHeightCapDensityFunction(
                info,
                ThreadLocal.withInitial(() -> new ChunkColumnCache(info))
            ))
        );

        private final MapInfo mapInfo;
        private final ThreadLocal<ChunkColumnCache> cache;

        private FmgHeightCapDensityFunction(MapInfo mapInfo, ThreadLocal<ChunkColumnCache> cache) {
            this.mapInfo = mapInfo;
            this.cache = cache;
        }

        @Override
        public double sample(DensityFunction.NoisePos pos) {
            int x = pos.blockX();
            int y = pos.blockY();
            int z = pos.blockZ();

            int height = cache.get().getHeight(x, z);
            return (height - y) * SCALE;
        }

        @Override
        public void fill(double[] densities, DensityFunction.EachApplier applier) {
            applier.fill(densities, this);
        }

        @Override
        public DensityFunction apply(DensityFunction.DensityFunctionVisitor visitor) {
            return visitor.apply(this);
        }

        @Override
        public double minValue() {
            return -(mapInfo.maxY() - mapInfo.minY() + 16) * SCALE;
        }

        @Override
        public double maxValue() {
            return (mapInfo.maxY() - mapInfo.minY() + 16) * SCALE;
        }

        @Override
        public net.minecraft.util.dynamic.CodecHolder<? extends DensityFunction> getCodecHolder() {
            // Not expected to be serialized (settings are stored by registry key), but required by the interface.
            return net.minecraft.util.dynamic.CodecHolder.of(CODEC);
        }
    }

    private static BlockState choosePathBlock(
            FMGRouteSampler.RouteKind kind,
            boolean isDesert,
            boolean isSnowy,
            boolean isTaiga,
            int worldX,
            int worldZ
    ) {
        // Deterministic pseudo-random based on world coordinates so
        // routes look varied but stable between runs.
        long seed = (long) worldX * 341873128712L ^ (long) worldZ * 132897987541L;
        seed ^= (seed >>> 13);
        seed *= 0x5DEECE66DL;
        int r = (int) (seed & 0x7FFFFFFF);

        if (kind == FMGRouteSampler.RouteKind.TRAIL) {
            if (isDesert) {
                // Desert trails: mostly sand with occasional birch planks.
                int v = r % 4; // 0-3
                if (v == 2) {
                    return Blocks.BIRCH_PLANKS.getDefaultState();
                }
                return Blocks.SAND.getDefaultState();
            }

            if (isSnowy) {
                // Snowy trails: cobblestone and gravel, no snow on top.
                int v = r % 4;
                if (v < 2) {
                    return Blocks.COBBLESTONE.getDefaultState();
                }
                return Blocks.GRAVEL.getDefaultState();
            }
            return Blocks.DIRT_PATH.getDefaultState();
        }

        if (kind == FMGRouteSampler.RouteKind.ROAD) {
            if (isDesert) {
                // Desert roads: terracotta core with granite variations.
                int v = r % 8;
                if (v < 3) {
                    return Blocks.TERRACOTTA.getDefaultState();
                } else if (v < 5) {
                    return Blocks.GRANITE.getDefaultState();
                } else if (v < 7) {
                    return Blocks.POLISHED_GRANITE.getDefaultState();
                }
                // Occasional sand to help it fade into surroundings.
                return Blocks.SAND.getDefaultState();
            }

            if (isSnowy) {
                // Snowy roads: cobblestone/gravel base with andesite/stone bricks.
                int v = r % 8;
                if (v < 2) {
                    return Blocks.COBBLESTONE.getDefaultState();
                } else if (v < 4) {
                    return Blocks.GRAVEL.getDefaultState();
                } else if (v < 6) {
                    return Blocks.ANDESITE.getDefaultState();
                }
                return Blocks.STONE_BRICKS.getDefaultState();
            }

            return Blocks.DIRT_PATH.getDefaultState();
        }

        return null;
    }

    private static boolean isBiome(RegistryEntry<Biome> biome, net.minecraft.registry.RegistryKey<Biome> key) {
        return biome.getKey().map(k -> k.equals(key)).orElse(false);
    }
}
