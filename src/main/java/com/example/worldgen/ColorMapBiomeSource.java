package com.example.worldgen;

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
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
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

            // Optional: a nested biome source that decides underground biomes.
            T undergroundSourceNode = input.get(ops.createString("underground_source"));
            DataResult<BiomeSource> undergroundSourceResult = undergroundSourceNode == null
                ? DataResult.success(null)
                : BiomeSource.CODEC.parse(ops, undergroundSourceNode);

            // Optional: block Y threshold below which we delegate to underground_source.
            // If absent, we default to (seaLevel - 24) to preserve prior behavior.
            T undergroundYNode = input.get(ops.createString("underground_y"));
            DataResult<Integer> undergroundYResult = undergroundYNode == null
                ? DataResult.success(null)
                    : ops.getNumberValue(undergroundYNode)
                        .map(Number::intValue)
                        .mapError(err -> "Field 'underground_y' must be a number: " + err);

            return mapInfoResult.flatMap(info -> undergroundSourceResult.flatMap(undergroundSource ->
                undergroundYResult.flatMap(undergroundY ->
                    registryOps.getEntryLookup(RegistryKeys.BIOME)
                        .map(lookup -> DataResult.success(new ColorMapBiomeSource(
                            lookup,
                            info,
                            undergroundSource,
                            undergroundY != null ? undergroundY : (info.seaLevel() - 24)
                        )))
                        .orElseGet(() -> DataResult.error(() -> "Biome registry missing while decoding ColorMapBiomeSource"))
                ))
            );
        }

        @Override
        public <T> RecordBuilder<T> encode(ColorMapBiomeSource value, DynamicOps<T> ops, RecordBuilder<T> builder) {
            builder.add(
                    ops.createString("map"),
                    MapInfo.CODEC.encodeStart(ops, value.mapInfo).result().orElseThrow()

            );

            if (value.undergroundSource != null) {
                builder.add(
                        ops.createString("underground_source"),
                        BiomeSource.CODEC.encodeStart(ops, value.undergroundSource).result().orElseThrow()
                );
                builder.add(ops.createString("underground_y"), ops.createInt(value.undergroundY));
            }

            return builder;
        }

        @Override
        public <T> Stream<T> keys(DynamicOps<T> ops) {
            return Stream.of(
                    ops.createString("map"),
                    ops.createString("underground_source"),
                    ops.createString("underground_y")
            );
        }
    };

    private final RegistryEntryLookup<Biome> biomeLookup;
    private final MapInfo mapInfo;

    /** Optional biome source used for underground selection. */
    private final BiomeSource undergroundSource;

    /** Block Y threshold at/below which we use {@link #undergroundSource} (when present). */
    private final int undergroundY;

    public ColorMapBiomeSource(RegistryEntryLookup<Biome> biomeLookup, MapInfo mapInfo) {
        this(biomeLookup, mapInfo, null, mapInfo.seaLevel() - 24);
    }

    public ColorMapBiomeSource(
            RegistryEntryLookup<Biome> biomeLookup,
            MapInfo mapInfo,
            BiomeSource undergroundSource,
            int undergroundY
    ) {
        this.biomeLookup = biomeLookup;
        this.mapInfo = mapInfo;
        this.undergroundSource = undergroundSource;
        this.undergroundY = undergroundY;
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

        // Cave biomes are 3D; our FMG mapping is 2D (XZ only). To keep FMG surface biomes while
        // still allowing cave biomes, switch to an underground biome selection below a depth.
        int blockY = quartToBlock(biomeY);
        if (blockY <= undergroundY) {
            if (undergroundSource != null) {
                return undergroundSource.getBiome(biomeX, biomeY, biomeZ, sampler);
            }

            // Back-compat fallback: previous deterministic cave-biome selection.
            RegistryEntry<Biome> cave = pickUndergroundBiome(biomeX, biomeY, biomeZ);
            if (cave != null) return cave;
        }

        // Use FMG's biome mapping based on FMG JSON data and biome registry.
        int blockX = quartToBlock(biomeX);
        int blockZ = quartToBlock(biomeZ);

        RegistryEntry<Biome> surfaceBiome = data.sampleFmgBiome(biomeLookup, blockX, blockZ);

        // Special rule: stop "ocean" biome from extending upward.
        // If FMG says this column is ocean, then for blocks at/above sea level
        // (y >= 63 by default), use BEACH instead.
        int seaLevel = mapInfo.seaLevel();
        if (surfaceBiome != null
                && blockY >= seaLevel
                && (surfaceBiome.isIn(BiomeTags.IS_OCEAN)
                        || surfaceBiome.matchesKey(BiomeKeys.OCEAN)
                        || surfaceBiome.matchesKey(BiomeKeys.DEEP_OCEAN))) {
            RegistryEntry.Reference<Biome> beach = biomeLookup.getOptional(BiomeKeys.BEACH).orElse(null);
            return beach != null ? beach : surfaceBiome;
        }

        return surfaceBiome;
    }

    private RegistryEntry<Biome> pickUndergroundBiome(int biomeX, int biomeY, int biomeZ) {
        // Cave biomes should form large coherent regions, not speckled noise.
        // Use low-frequency deterministic value noise in XZ so lush/dripstone/deep dark
        // appear as big contiguous blobs.

        int blockY = quartToBlock(biomeY);

        // Depth factor: 0 near the underground threshold, 1 deep below.
        int thresholdY = undergroundY;
        double depth01 = clamp01((thresholdY - blockY) / 96.0);

        // Big deep-dark pockets when very deep.
        if (blockY <= -48) {
            double dd = coherentNoise2D(biomeX, biomeZ, 96, 0xD00DFEEDL);
            // Deeper => more likely deep dark.
            double ddThreshold = lerp(0.70, 0.48, clamp01((-48 - blockY) / 96.0));
            if (dd > ddThreshold) {
                return biomeLookup.getOptional(BiomeKeys.DEEP_DARK).orElse(null);
            }
        }

        // Lush/dripstone blobs.
        double cave = coherentNoise2D(biomeX, biomeZ, 64, 0xCAFECAFE1234L);
        double band = lerp(0.55, 0.30, depth01);

        if (cave > band) {
            return biomeLookup.getOptional(BiomeKeys.LUSH_CAVES).orElse(null);
        }
        if (cave < -band) {
            return biomeLookup.getOptional(BiomeKeys.DRIPSTONE_CAVES).orElse(null);
        }

        return null;
    }

    private static double coherentNoise2D(int x, int z, int scale, long salt) {
        // Value noise on a lattice with bilinear interpolation.
        // Inputs are in biome "quart" coords; larger scale => bigger contiguous regions.
        if (scale <= 0) {
            return 0.0;
        }

        int x0 = floorDiv(x, scale);
        int z0 = floorDiv(z, scale);
        int x1 = x0 + 1;
        int z1 = z0 + 1;

        double fx = (x - (double) x0 * (double) scale) / (double) scale;
        double fz = (z - (double) z0 * (double) scale) / (double) scale;

        double u = smoothstep01(fx);
        double v = smoothstep01(fz);

        double n00 = hashToSignedUnit(x0, z0, salt);
        double n10 = hashToSignedUnit(x1, z0, salt);
        double n01 = hashToSignedUnit(x0, z1, salt);
        double n11 = hashToSignedUnit(x1, z1, salt);

        double nx0 = lerp(n00, n10, u);
        double nx1 = lerp(n01, n11, u);
        return lerp(nx0, nx1, v);
    }

    private static int floorDiv(int a, int b) {
        // Java's Math.floorDiv is fine, but keeping it local avoids extra boxing/imports.
        int r = a / b;
        int m = a % b;
        if ((m != 0) && ((a ^ b) < 0)) {
            r--;
        }
        return r;
    }

    private static double hashToSignedUnit(int x, int z, long salt) {
        long h = 1469598103934665603L ^ salt;
        h = (h ^ x) * 1099511628211L;
        h = (h ^ z) * 1099511628211L;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);

        // Convert to [0,1) using the top 53 bits, then to [-1,1].
        long mantissa = (h >>> 11) & ((1L << 53) - 1);
        double unit01 = mantissa / (double) (1L << 53);
        return (unit01 * 2.0) - 1.0;
    }

    private static double smoothstep01(double t) {
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;
        return t * t * (3.0 - 2.0 * t);
    }

    private static double clamp01(double v) {
        if (v <= 0.0) return 0.0;
        if (v >= 1.0) return 1.0;
        return v;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    @Override
    protected java.util.stream.Stream<RegistryEntry<Biome>> biomeStream() {
        // Expose the palette used by FMGBiomeMapper so the engine has
        // a reasonable biome set to work with.
        Stream<RegistryEntry<Biome>> fmg = FMGBiomeMapper.paletteKeys().stream()
                .map(key -> biomeLookup.getOptional(key))
                .flatMap(optional -> optional.map(Stream::of).orElseGet(Stream::empty));

        if (undergroundSource == null) {
            return fmg;
        }

        return Stream.concat(fmg, undergroundSource.getBiomes().stream()).distinct();
    }

    private static int quartToBlock(int coord) {
        return coord << 2;
    }
}
