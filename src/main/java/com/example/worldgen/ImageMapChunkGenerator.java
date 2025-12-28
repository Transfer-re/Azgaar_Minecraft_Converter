package com.example.worldgen;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.example.worldmap.MapInfo;
import com.example.worldmap.runtime.MapImageCache;
import com.example.worldmap.runtime.MapImageData;
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
        
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = chunkPos.getStartX() + localX;
                int worldZ = chunkPos.getStartZ() + localZ;
                int target = data.sampleHeightY(worldX, worldZ);

                // Clear everything above the target height so the vanilla
                // surface rules can rebuild the top blocks (grass, sand, etc.).
                for (int y = target + 1; y < maxY; y++) {
                    mutable.set(worldX, y, worldZ);
                    chunk.setBlockState(mutable, Blocks.AIR.getDefaultState(), false);
                }

                // Ensure there is solid terrain up to our target FMG height,
                // but do not try to decide the exact surface block here.
                for (int y = minY + 1; y <= target && y < maxY; y++) {
                    mutable.set(worldX, y, worldZ);
                    if (chunk.getBlockState(mutable).isAir()) {
                        chunk.setBlockState(mutable, Blocks.STONE.getDefaultState(), false);
                    }
                }

                if (target <= minY + 1 || target >= maxY - 1) {
                    continue;
                }

                // Biome-aware surface placement similar to vanilla palettes.
                int biomeX = worldX >> 2;
                int biomeZ = worldZ >> 2;
                int biomeY = target >> 2;
                RegistryEntry<Biome> biome = chunk.getBiomeForNoiseGen(biomeX, biomeY, biomeZ);

                boolean isDesert = isBiome(biome, BiomeKeys.DESERT) || isBiome(biome, BiomeKeys.BADLANDS);
                boolean isSnowy = isBiome(biome, BiomeKeys.SNOWY_PLAINS) || isBiome(biome, BiomeKeys.SNOWY_TAIGA)
                        || isBiome(biome, BiomeKeys.SNOWY_SLOPES) || isBiome(biome, BiomeKeys.FROZEN_PEAKS);
                boolean isTaiga = isBiome(biome, BiomeKeys.TAIGA);
                boolean isOcean = isBiome(biome, BiomeKeys.OCEAN) || isBiome(biome, BiomeKeys.DEEP_OCEAN);

                // Oceans: sand floor and water up to sea level.
                if (target < seaLevel - 2 && isOcean) {
                    // Ocean floor
                    mutable.set(worldX, target, worldZ);
                    chunk.setBlockState(mutable, Blocks.SAND.getDefaultState(), false);

                    // A few layers of sandstone under the floor
                    for (int y = target - 1; y >= target - 3 && y > minY; y--) {
                        mutable.set(worldX, y, worldZ);
                        if (!chunk.getBlockState(mutable).isAir()) {
                            chunk.setBlockState(mutable, Blocks.SANDSTONE.getDefaultState(), false);
                        }
                    }

                    // Water column
                    for (int y = target + 1; y <= seaLevel && y < maxY; y++) {
                        mutable.set(worldX, y, worldZ);
                        chunk.setBlockState(mutable, Blocks.WATER.getDefaultState(), false);
                    }
                    continue;
                }

                // Land surface palettes.
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
                    // Snowy top with dirt underneath.
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
                    // Default grass/dirt stack.
                    chunk.setBlockState(mutable, Blocks.GRASS_BLOCK.getDefaultState(), false);
                    for (int y = target - 1; y >= target - 3 && y > minY; y--) {
                        mutable.set(worldX, y, worldZ);
                        if (!chunk.getBlockState(mutable).isAir()) {
                            chunk.setBlockState(mutable, Blocks.DIRT.getDefaultState(), false);
                        }
                    }
                }
            }
        }
    }

    private static boolean isBiome(RegistryEntry<Biome> biome, net.minecraft.registry.RegistryKey<Biome> key) {
        return biome.getKey().map(k -> k.equals(key)).orElse(false);
    }
}
