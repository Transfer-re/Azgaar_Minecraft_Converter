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

    /** The content profile this world generates with (biome mapping + block palettes). */
    private com.example.profile.FmgContentProfile profile() {
        com.example.profile.FmgContentProfile p = profile;
        if (p == null) {
            p = com.example.profile.FmgProfiles.get(mapInfo.profile());
            profile = p;
        }
        return p;
    }

    private volatile com.example.profile.FmgContentProfile profile;

    /**
     * Mushroom fields is the one vanilla biome that has no place on a fantasy
     * continent map -- it only exists in vanilla as a rare mid-ocean freak. The
     * vanilla table hands it back at the most-oceanic continentalness slice, so
     * swap it for deep ocean (its natural neighbour there).
     */
    private RegistryEntry<Biome> filterBiome(RegistryEntry<Biome> biome) {
        if (biome != null && biome.matchesKey(BiomeKeys.MUSHROOM_FIELDS)) {
            RegistryEntry.Reference<Biome> replacement =
                    biomeLookup.getOptional(BiomeKeys.DEEP_OCEAN).orElse(null);
            if (replacement != null) {
                return replacement;
            }
        }
        return biome;
    }

    @Override
    protected MapCodec<? extends BiomeSource> getCodec() {
        return CODEC;
    }

    @Override
    public RegistryEntry<Biome> getBiome(int biomeX, int biomeY, int biomeZ, MultiNoiseUtil.MultiNoiseSampler sampler) {
        MapImageData data = MapImageCache.get(mapInfo);
        int blockX = quartToBlock(biomeX);
        int blockY = quartToBlock(biomeY);
        int blockZ = quartToBlock(biomeZ);
        int seaLevel = mapInfo.seaLevel();

        // Carved rivers get minecraft:river along the channel, near the water,
        // so biome-colour mods tint them. Both code paths, near sea level only.
        if (blockY >= seaLevel - 24 && blockY <= seaLevel + 90) {
            com.example.fmg.FMGRiverNetwork rivers = com.example.fmg.FMGRiverNetwork.get(
                    mapInfo.fmgExport(), data.fmgData(), mapInfo.minY(), mapInfo.maxY(), seaLevel);
            com.example.fmg.FMGRiverNetwork.Hit riverHit = rivers.query(blockX, blockZ);
            if (riverHit != null && riverHit.distance <= riverHit.sample.halfWidth + 1.0) {
                int w = riverHit.sample.waterSurfaceY;
                if (blockY >= w - riverHit.sample.centerDepth - 4 && blockY <= w + 6) {
                    RegistryEntry.Reference<Biome> river =
                            biomeLookup.getOptional(profile().biomeKey(profile().biomes.river)).orElse(null);
                    if (river != null) return river;
                }
            }
        }

        // ---- cave picker + name+variant FMG biome mapping ----
        if (blockY <= undergroundY) {
            if (undergroundSource != null) {
                return undergroundSource.getBiome(biomeX, biomeY, biomeZ, sampler);
            }
            RegistryEntry<Biome> cave = pickUndergroundBiome(biomeX, biomeY, biomeZ);
            if (cave != null) return cave;
        }

        int[] at = com.example.fmg.FMGBiomeMapper.warped(profile(), blockX, blockZ);
        RegistryEntry<Biome> surfaceBiome = filterBiome(data.sampleFmgBiome(biomeLookup, at[0], at[1]));
        if (surfaceBiome != null && blockY >= seaLevel && isOceanLike(surfaceBiome)) {
            boolean cold = surfaceBiome.matchesKey(BiomeKeys.FROZEN_OCEAN)
                    || surfaceBiome.matchesKey(BiomeKeys.DEEP_FROZEN_OCEAN)
                    || surfaceBiome.matchesKey(BiomeKeys.COLD_OCEAN)
                    || surfaceBiome.matchesKey(BiomeKeys.DEEP_COLD_OCEAN);
            RegistryEntry.Reference<Biome> beach = biomeLookup.getOptional(profile().biomeKey(
                    cold ? profile().biomes.snowyBeach : profile().biomes.beach)).orElse(null);
            return beach != null ? beach : surfaceBiome;
        }
        return surfaceBiome;
    }

    /**
     * Id of the land biome whose ground palette a column uses: the same warped
     * map lookup as {@link #getBiome}, without the river and beach overrides.
     */
    public String landBiomeId(int blockX, int blockZ) {
        MapImageData data = MapImageCache.get(mapInfo);
        return com.example.fmg.FMGBiomeMapper.resolveKey(mapInfo.fmgExport(), mapInfo.richBiomes(), profile(),
                data.fmgData(), blockX, blockZ).getValue().toString();
    }

    private static boolean isOceanLike(RegistryEntry<Biome> biome) {
        return biome.isIn(BiomeTags.IS_OCEAN)
                || biome.matchesKey(BiomeKeys.OCEAN)
                || biome.matchesKey(BiomeKeys.DEEP_OCEAN);
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
                return biomeLookup.getOptional(profile().biomeKey(profile().biomes.deepDark)).orElse(null);
            }
        }

        // Lush/dripstone blobs.
        double cave = coherentNoise2D(biomeX, biomeZ, 64, 0xCAFECAFE1234L);
        double band = lerp(0.55, 0.30, depth01);

        if (cave > band) {
            return biomeLookup.getOptional(profile().biomeKey(profile().biomes.lushCaves)).orElse(null);
        }
        if (cave < -band) {
            return biomeLookup.getOptional(profile().biomeKey(profile().biomes.dripstoneCaves)).orElse(null);
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
        // Every biome the name+variant mapper (FMGBiomeMapper/FMGBiomeVariantMap)
        // can produce, plus RIVER.
        Stream<net.minecraft.registry.RegistryKey<Biome>> keys =
                Stream.concat(FMGBiomeMapper.paletteKeys(profile()).stream(),
                        Stream.of(profile().biomeKey(profile().biomes.river)));

        Stream<RegistryEntry<Biome>> resolved = keys
                .map(key -> biomeLookup.getOptional(key))
                .<RegistryEntry<Biome>>flatMap(optional -> optional.map(Stream::of).orElseGet(Stream::empty))
                .distinct();

        if (undergroundSource == null) {
            return resolved;
        }
        return Stream.concat(resolved, undergroundSource.getBiomes().stream()).distinct();
    }

    private static int quartToBlock(int coord) {
        return coord << 2;
    }
}
