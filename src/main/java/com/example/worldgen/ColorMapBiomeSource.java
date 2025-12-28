package com.example.worldgen;

import java.util.Optional;
import java.util.stream.Stream;

import com.example.fmg.FMGBiomeMapper;
import com.example.worldmap.MapInfo;
import com.example.worldmap.runtime.MapImageCache;
import com.example.worldmap.runtime.MapImageData;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;

import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;

/**
 * Biome source that assigns biomes from FMG map data.
 */
public final class ColorMapBiomeSource extends BiomeSource {

    public static final MapCodec<ColorMapBiomeSource> CODEC = new MapCodec<>() {
        @Override
        public <T> DataResult<ColorMapBiomeSource> decode(DynamicOps<T> ops, MapLike<T> input) {
            if (!(ops instanceof RegistryOps<?> registryOps)) {
                return DataResult.error(() -> "ColorMapBiomeSource requires registry-backed ops");
            }

            T mapNode = input.get(ops.createString("map"));
            if (mapNode == null) {
                return DataResult.error(() -> "Missing 'map' field for ColorMapBiomeSource");
            }

            DataResult<MapInfo> mapInfoResult = MapInfo.CODEC.parse(ops, mapNode);

            return mapInfoResult.flatMap(info ->
                    registryOps.getEntryLookup(RegistryKeys.BIOME)
                            .map(lookup -> DataResult.success(new ColorMapBiomeSource(lookup, info)))
                            .orElseGet(() -> DataResult.error(() -> "Biome registry missing while decoding ColorMapBiomeSource"))
            );
        }

        @Override
        public <T> RecordBuilder<T> encode(ColorMapBiomeSource value, DynamicOps<T> ops, RecordBuilder<T> builder) {
            return builder.add(
                    ops.createString("map"),
                    MapInfo.CODEC.encodeStart(ops, value.mapInfo).result().orElseThrow()
            );
        }

        @Override
        public <T> Stream<T> keys(DynamicOps<T> ops) {
            return Stream.of(ops.createString("map"));
        }
    };

    private final RegistryEntryLookup<Biome> biomeLookup;
    private final MapInfo mapInfo;

    public ColorMapBiomeSource(RegistryEntryLookup<Biome> biomeLookup, MapInfo mapInfo) {
        this.biomeLookup = biomeLookup;
        this.mapInfo = mapInfo;
    }

    public MapInfo mapInfo() {
        return mapInfo;
    }

    @Override
    protected MapCodec<? extends BiomeSource> getCodec() {
        return CODEC;
    }

    @Override
    public RegistryEntry<Biome> getBiome(int biomeX, int biomeY, int biomeZ, MultiNoiseUtil.MultiNoiseSampler sampler) {
        MapImageData data = MapImageCache.get(mapInfo);

        // Use FMG's biome mapping based on FMG JSON data and biome registry.
        return data.sampleFmgBiome(biomeLookup, quartToBlock(biomeX), quartToBlock(biomeZ));
    }

    @Override
    protected java.util.stream.Stream<RegistryEntry<Biome>> biomeStream() {
        // Expose the palette used by FMGBiomeMapper so the engine has
        // a reasonable biome set to work with.
        return FMGBiomeMapper.paletteKeys().stream()
                .map(key -> biomeLookup.getOptional(key))
                .flatMap(optional -> optional.map(Stream::of).orElseGet(Stream::empty));
    }

    private static int quartToBlock(int coord) {
        return coord << 2;
    }
}
