package com.example.worldmap;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Datapack-driven definition describing how to load an FMG export.
 *
 * This is now a simple value object used directly by the
 * custom chunk generator and (optionally) biome source,
 * without going through a separate dynamic registry.
 */
public record MapInfo(
        String fmgExport,
        int minY,
        int maxY,
        int seaLevel
) {

    public static final Codec<MapInfo> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("fmg_export").forGetter(MapInfo::fmgExport),
                    Codec.INT.optionalFieldOf("min_y", -64).forGetter(MapInfo::minY),
                    Codec.INT.optionalFieldOf("max_y", 320).forGetter(MapInfo::maxY),
                    Codec.INT.optionalFieldOf("sea_level", 63).forGetter(MapInfo::seaLevel)
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
    }
}
