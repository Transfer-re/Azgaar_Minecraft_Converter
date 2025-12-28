package com.example.worldmap.runtime;

import java.util.function.Function;

import com.example.fmg.FMGBiomeMapper;
import com.example.fmg.FMGHeightSampler;
import com.example.fmg.FMGMapData;
import com.example.worldmap.MapInfo;

import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;

/**
 * FMG-backed height/biome sampling cache.
 */
public final class MapImageData {

    private final MapInfo definition;
    private final FMGMapData fmgData;

    private MapImageData(MapInfo definition, FMGMapData fmgData) {
        this.definition = definition;
        this.fmgData = fmgData;
    }

    public static MapImageData from(MapInfo info) {
        FMGMapData data = FMGMapDataCache.load(info.fmgExport());
        return new MapImageData(info, data);
    }

    public MapInfo definition() {
        return definition;
    }

    /** Underlying FMG map data backing this image. */
    public FMGMapData fmgData() {
        return fmgData;
    }

    public int sampleHeightY(int worldX, int worldZ) {
        int sampled = FMGHeightSampler.sampleHeight(fmgData, worldX, worldZ);
        int min = definition.minY();
        int max = definition.maxY();
        return Math.max(min, Math.min(max, sampled));
    }

    /** Resolve FMG biomes using the provided biome registry lookup. */
    public RegistryEntry<Biome> sampleFmgBiome(RegistryEntryLookup<Biome> biomes, int worldX, int worldZ) {
        return FMGBiomeMapper.resolve(biomes, fmgData, worldX, worldZ);
    }

    public RegistryEntry<Biome> sampleFmgBiome(
            Function<RegistryKey<Biome>, RegistryEntry<Biome>> resolver,
            RegistryEntry<Biome> fallback,
            int worldX,
            int worldZ
    ) {
        RegistryEntry<Biome> resolved = FMGBiomeMapper.resolve(resolver, fmgData, worldX, worldZ);
        return resolved != null ? resolved : fallback;
    }
}
