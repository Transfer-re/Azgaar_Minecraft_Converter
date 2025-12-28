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
        delegate.buildSurface(region, structures, noiseConfig, chunk);
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
            }
        }
    }
}
