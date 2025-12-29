package com.example.worldgen;

import java.util.List;
import java.util.concurrent.CompletableFuture;

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
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.ChunkRegion;

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
                private final MapInfo mapInfo;

    public ImageMapChunkGenerator(
            BiomeSource biomeSource,
            RegistryEntry<ChunkGeneratorSettings> settings,
            MapInfo mapInfo
    ) {
        super(biomeSource);
        this.delegate = new NoiseChunkGenerator(biomeSource, settings);
        this.delegateSettings = settings;
        this.mapInfo = mapInfo;
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
        return MapImageCache.get(mapInfo).sampleHeightY(x, z);
    }

    @Override
    public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
        return delegate.getColumnSample(x, z, world, noiseConfig);
    }

    @Override
    public CompletableFuture<Chunk> populateNoise(Blender blender, NoiseConfig noiseConfig,
            StructureAccessor structureAccessor, Chunk chunk) {
        return delegate.populateNoise(blender, noiseConfig, structureAccessor, chunk)
                .thenApply(result -> {
                    rewriteTerrain(result);
                    return result;
                });
    }

    @Override
    public void appendDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
        text.add("FMG map: " + mapInfo.fmgExport());
    }

    private void rewriteTerrain(Chunk chunk) {
        MapImageData data = MapImageCache.get(mapInfo);
        ChunkPos chunkPos = chunk.getPos();
        int minY = getMinimumY();
        int maxY = getWorldHeight();
        int seaLevel = mapInfo.seaLevel();
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        // 1) Sample FMG height + route kind for this chunk
        int[][] heights = new int[16][16];
        FMGRouteSampler.RouteKind[][] routes = new FMGRouteSampler.RouteKind[16][16];

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = chunkPos.getStartX() + localX;
                int worldZ = chunkPos.getStartZ() + localZ;

                int target = data.sampleHeightY(worldX, worldZ);
                routes[localX][localZ] = FMGRouteSampler.sampleRoute(data.fmgData(), worldX, worldZ);

                // Carve river beds based on FMG river samples. This pulls the
                // terrain down below sea level in a smooth cross‑section whose
                // width and depth depend on the river width at this point.
                FMRiverSampler.RiverQuery rq = FMRiverSampler.query(worldX, worldZ);
                if (rq.hit) {
                    double carved = FMRiverSampler.carvedHeight(rq, seaLevel);
                    int riverBed = (int) Math.floor(carved);
                    target = Math.min(target, riverBed);
                }

                heights[localX][localZ] = target;
            }
        }

        // 2) Smooth route heights (only land, only along routes)
        for (int pass = 0; pass < 2; pass++) {
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    if (routes[localX][localZ] == FMGRouteSampler.RouteKind.NONE) continue;

                    int h = heights[localX][localZ];
                    if (h < seaLevel) continue; // don't touch water columns

                    int minNeighbour = h;

                    // 4‑neighbourhood along the route
                    if (localX > 0 && routes[localX - 1][localZ] != FMGRouteSampler.RouteKind.NONE) {
                        int nh = heights[localX - 1][localZ];
                        if (nh >= seaLevel) minNeighbour = Math.min(minNeighbour, nh);
                    }
                    if (localX < 15 && routes[localX + 1][localZ] != FMGRouteSampler.RouteKind.NONE) {
                        int nh = heights[localX + 1][localZ];
                        if (nh >= seaLevel) minNeighbour = Math.min(minNeighbour, nh);
                    }
                    if (localZ > 0 && routes[localX][localZ - 1] != FMGRouteSampler.RouteKind.NONE) {
                        int nh = heights[localX][localZ - 1];
                        if (nh >= seaLevel) minNeighbour = Math.min(minNeighbour, nh);
                    }
                    if (localZ < 15 && routes[localX][localZ + 1] != FMGRouteSampler.RouteKind.NONE) {
                        int nh = heights[localX][localZ + 1];
                        if (nh >= seaLevel) minNeighbour = Math.min(minNeighbour, nh);
                    }

                    // If this column sticks up more than 1 block above its
                    // neighbours on the route, carve it down.
                    if (h - minNeighbour > 1) {
                        int newH = minNeighbour + 1;
                        if (newH < seaLevel) newH = seaLevel;
                        heights[localX][localZ] = newH;
                    }
                }
            }
        }

        // 3) Build terrain using adjusted heights
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = chunkPos.getStartX() + localX;
                int worldZ = chunkPos.getStartZ() + localZ;
                int target = heights[localX][localZ];

                // Clear everything above the target height
                for (int y = target + 1; y < maxY; y++) {
                    mutable.set(worldX, y, worldZ);
                    chunk.setBlockState(mutable, Blocks.AIR.getDefaultState(), false);
                }

                // Solid terrain up to target
                for (int y = minY + 1; y <= target && y < maxY; y++) {
                    mutable.set(worldX, y, worldZ);
                    if (chunk.getBlockState(mutable).isAir()) {
                        chunk.setBlockState(mutable, Blocks.STONE.getDefaultState(), false);
                    }
                }

                if (target <= minY + 1 || target >= maxY - 1) {
                    continue;
                }

                // Water columns unchanged, still height‑driven
                if (target < seaLevel) {
                    int floorY = target;
                    if (floorY >= seaLevel) {
                        floorY = seaLevel - 1;
                    }
                    if (floorY <= minY + 1) {
                        continue;
                    }

                    mutable.set(worldX, floorY, worldZ);
                    chunk.setBlockState(mutable, Blocks.SAND.getDefaultState(), false);

                    for (int y = floorY - 1; y >= floorY - 3 && y > minY; y--) {
                        mutable.set(worldX, y, worldZ);
                        if (!chunk.getBlockState(mutable).isAir()) {
                            chunk.setBlockState(mutable, Blocks.SANDSTONE.getDefaultState(), false);
                        }
                    }

                    for (int y = floorY + 1; y <= seaLevel && y < maxY; y++) {
                        mutable.set(worldX, y, worldZ);
                        chunk.setBlockState(mutable, Blocks.WATER.getDefaultState(), false);
                    }
                    continue;
                }

                // Land surface (same as before, but using smoothed height)
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
                        if (!chunk.getBlockState(mutable).isAir()) {
                            chunk.setBlockState(mutable, Blocks.SANDSTONE.getDefaultState(), false);
                        }
                    }
                } else if (isSnowy) {
                    chunk.setBlockState(mutable, Blocks.SNOW_BLOCK.getDefaultState(), false);
                    for (int y = target - 1; y >= target - 3 && y > minY; y--) {
                        mutable.set(worldX, y, worldZ);
                        if (!chunk.getBlockState(mutable).isAir()) {
                            chunk.setBlockState(mutable, Blocks.DIRT.getDefaultState(), false);
                        }
                    }
                } else if (isTaiga) {
                    chunk.setBlockState(mutable, Blocks.PODZOL.getDefaultState(), false);
                    for (int y = target - 1; y >= target - 3 && y > minY; y--) {
                        mutable.set(worldX, y, worldZ);
                        if (!chunk.getBlockState(mutable).isAir()) {
                            chunk.setBlockState(mutable, Blocks.DIRT.getDefaultState(), false);
                        }
                    }
                } else {
                    chunk.setBlockState(mutable, Blocks.GRASS_BLOCK.getDefaultState(), false);
                    for (int y = target - 1; y >= target - 3 && y > minY; y--) {
                        mutable.set(worldX, y, worldZ);
                        if (!chunk.getBlockState(mutable).isAir()) {
                            chunk.setBlockState(mutable, Blocks.DIRT.getDefaultState(), false);
                        }
                    }
                }

                // 4) Overlay route blocks at the smoothed height
                FMGRouteSampler.RouteKind routeKind = routes[localX][localZ];
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

        boolean grassy = !isDesert && !isSnowy;

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
