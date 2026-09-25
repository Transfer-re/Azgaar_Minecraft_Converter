package com.example.worldmap;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Value object describing how to load and shape an FMG export.
 *
 * <p>Embedded (via {@link #CODEC}) in both the {@code image_map} chunk generator
 * and the {@code color_map} biome source, so it is serialised into
 * {@code level.dat}. The {@code fmg_export} + {@code min_y}/{@code max_y}/
 * {@code sea_level} fields come from the world preset / datapack; the trailing
 * fields ({@code scale}, {@code surface_detail}, {@code detail_amplitude},
 * {@code climate_biomes}) are per-world overrides written by the create-world
 * screen. All trailing fields are optional so existing saves keep loading.</p>
 *
 * <p>{@code richBiomes} (JSON key kept as {@code climate_biomes} for save
 * compatibility) toggles {@code FMGBiomeVariantMap}'s name+variant biome
 * mapping; off falls back to the bare 1:1 name mapping with no variants.</p>
 */
public record MapInfo(
        String fmgExport,
        int minY,
        int maxY,
        int seaLevel,
        double scale,
        boolean surfaceDetail,
        double detailAmplitude,
        boolean richBiomes,
        String profile
) {

    /** World blocks per Azgaar map pixel. */
    public static final double DEFAULT_SCALE = 16.0;
    /** Matches {@code HeightmapPostConfig.Detail} defaults. */
    public static final double DEFAULT_DETAIL_AMPLITUDE = 6.0;
    /** Content profile id -- see {@code com.example.profile.FmgContentProfile}. */
    public static final String DEFAULT_PROFILE = "vanilla";

    public static final Codec<MapInfo> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("fmg_export").forGetter(MapInfo::fmgExport),
                    Codec.INT.optionalFieldOf("min_y", -64).forGetter(MapInfo::minY),
                    Codec.INT.optionalFieldOf("max_y", 320).forGetter(MapInfo::maxY),
                    Codec.INT.optionalFieldOf("sea_level", 63).forGetter(MapInfo::seaLevel),
                    Codec.DOUBLE.optionalFieldOf("scale", 12.0).forGetter(MapInfo::scale),
                    Codec.BOOL.optionalFieldOf("surface_detail", true).forGetter(MapInfo::surfaceDetail),
                    Codec.DOUBLE.optionalFieldOf("detail_amplitude", DEFAULT_DETAIL_AMPLITUDE)
                            .forGetter(MapInfo::detailAmplitude),
                    Codec.BOOL.optionalFieldOf("climate_biomes", true).forGetter(MapInfo::richBiomes),
                    Codec.STRING.optionalFieldOf("profile", DEFAULT_PROFILE).forGetter(MapInfo::profile)
            ).apply(instance, MapInfo::new)
    );

    public MapInfo {
        Objects.requireNonNull(fmgExport, "fmg_export");

        if (fmgExport.isBlank()) {
            throw new IllegalArgumentException("fmg_export must not be blank");
        }
        if (maxY <= minY) {
            throw new IllegalArgumentException("max_y must be greater than min_y");
        }
        if (!Double.isFinite(scale) || scale <= 0.0) {
            scale = DEFAULT_SCALE;
        }
        if (!Double.isFinite(detailAmplitude) || detailAmplitude < 0.0) {
            detailAmplitude = DEFAULT_DETAIL_AMPLITUDE;
        }
        if (profile == null || profile.isBlank()) {
            profile = DEFAULT_PROFILE;
        }
    }

    /** Legacy 4-arg constructor -- keeps callers that don't care about the overrides working. */
    public MapInfo(String fmgExport, int minY, int maxY, int seaLevel) {
        this(fmgExport, minY, maxY, seaLevel, DEFAULT_SCALE, true, DEFAULT_DETAIL_AMPLITUDE, true, DEFAULT_PROFILE);
    }
}
