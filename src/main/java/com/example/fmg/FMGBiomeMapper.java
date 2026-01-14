package com.example.fmg;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;

/**
 * Maps FMG biome data to vanilla Minecraft biomes.
 * Minecraft 1.21.4 compatible (dynamic registries).
 */
public final class FMGBiomeMapper {

    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]");
    private static final Map<String, RegistryKey<Biome>> NAMED_MAPPINGS = FMGBiomeMappingConfig.loadOrCreateMappings();
    private static final Set<RegistryKey<Biome>> PALETTE_KEYS = buildPaletteKeys();

    private FMGBiomeMapper() {}

    // ===== Public API =====

    public static Set<RegistryKey<Biome>> paletteKeys() {
        return PALETTE_KEYS;
    }

    public static RegistryEntry<Biome> resolve(
            RegistryEntryLookup<Biome> biomes,
            FMGMapData mapData,
            int worldX,
            int worldZ
    ) {
        return resolve(key -> lookup(biomes, key), mapData, worldX, worldZ);
    }

    public static RegistryEntry<Biome> resolve(
            RegistryEntryLookup<Biome> biomes,
            FMGMapData mapData,
            FMGCell cell
    ) {
        return resolve(key -> lookup(biomes, key), mapData, cell);
    }

    public static RegistryEntry<Biome> resolve(
            Function<RegistryKey<Biome>, RegistryEntry<Biome>> resolver,
            FMGMapData mapData,
            int worldX,
            int worldZ
    ) {
        if (mapData == null || mapData.getInfo() == null) {
            return lookup(resolver, BiomeKeys.PLAINS);
        }

        double offsetX = -(mapData.getInfo().getWidth() * FMGHeightSampler.SAMPLE_SCALE) / 2.0;
        double offsetZ = -(mapData.getInfo().getHeight() * FMGHeightSampler.SAMPLE_SCALE) / 2.0;

        double fmgX = (worldX - offsetX) / FMGHeightSampler.SAMPLE_SCALE;
        double fmgY = (worldZ - offsetZ) / FMGHeightSampler.SAMPLE_SCALE;

        FMGCell cell = mapData.findNearestCell(fmgX, fmgY);
        return resolve(resolver, mapData, cell);
    }

    public static RegistryEntry<Biome> resolve(
            Function<RegistryKey<Biome>, RegistryEntry<Biome>> resolver,
            FMGMapData mapData,
            FMGCell cell
    ) {
        if (cell == null) {
            return lookup(resolver, BiomeKeys.PLAINS);
        }

        FMGBiome biomeDef = mapData.getBiome(cell.getBiome());
        if (biomeDef != null) {
            // Temporary debug to understand why mappings fall back to PLAINS
            // if (cell.getI() % 1000 == 0) {
            //     System.out.println("[FMG] Cell " + cell.getI() + " biome id=" + cell.getBiome()
            //             + " name='" + biomeDef.getName() + "'");
            // }
            RegistryEntry<Biome> named = mapByName(
                    resolver,
                    biomeDef.getName(),
                    0.5,    // height placeholder; mapping is 1:1 by name
                    false,  // cold placeholder
                    false   // hot placeholder
            );
            if (named != null) {
                return named;
            }
        }
        return lookup(resolver, BiomeKeys.PLAINS);
    }

    // ===== Internals =====

        private static RegistryEntry<Biome> mapByName(
            Function<RegistryKey<Biome>, RegistryEntry<Biome>> resolver,
            String rawName,
            double height,
            boolean cold,
            boolean hot
    ) {
        if (rawName == null || rawName.isEmpty()) {
            return null;
        }

        String normalized = NON_WORD
                .matcher(rawName.toLowerCase(Locale.ROOT))
                .replaceAll("");

        RegistryKey<Biome> key = NAMED_MAPPINGS.get(normalized);
        if (key != null) {
            return lookup(resolver, key);
        }

        if (normalized.contains("mount")) {
            return lookup(resolver, cold ? BiomeKeys.SNOWY_SLOPES : BiomeKeys.STONY_PEAKS);
        }
        if (normalized.contains("swamp") || normalized.contains("wet")) {
            return lookup(resolver, cold ? BiomeKeys.SNOWY_TAIGA : BiomeKeys.SWAMP);
        }
        if (normalized.contains("forest")) {
            return lookup(resolver, hot ? BiomeKeys.JUNGLE : (cold ? BiomeKeys.TAIGA : BiomeKeys.FOREST));
        }
        if (normalized.contains("tundra") || normalized.contains("glacier")) {
            return lookup(resolver, BiomeKeys.SNOWY_TAIGA);
        }
        if (normalized.contains("steppe")) {
            return lookup(resolver, BiomeKeys.WINDSWEPT_SAVANNA);
        }

        if (height < 0.2) {
            return lookup(resolver, BiomeKeys.MANGROVE_SWAMP);
        }

        return null;
    }

    private static RegistryEntry<Biome> lookup(
            Function<RegistryKey<Biome>, RegistryEntry<Biome>> resolver,
            RegistryKey<Biome> key
    ) {
        RegistryEntry<Biome> entry = resolver.apply(key);
        if (entry != null) {
            return entry;
        }
        RegistryEntry<Biome> fallback = resolver.apply(BiomeKeys.PLAINS);
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalStateException("Missing FMG biome resolver for key " + key.getValue());
    }

    private static RegistryEntry<Biome> lookup(
            RegistryEntryLookup<Biome> biomes,
            RegistryKey<Biome> key
    ) {
        return biomes.getOptional(key)
                .orElseGet(() ->
                        biomes.getOptional(BiomeKeys.PLAINS)
                                .orElseThrow(() ->
                                        new IllegalStateException("Plains biome missing"))
                );
    }

    private static Set<RegistryKey<Biome>> buildPaletteKeys() {
        Set<RegistryKey<Biome>> keys = new LinkedHashSet<>(NAMED_MAPPINGS.values());
        Collections.addAll(
                keys,
                BiomeKeys.DEEP_OCEAN,
                BiomeKeys.OCEAN,
                BiomeKeys.BEACH,
            BiomeKeys.LUSH_CAVES,
            BiomeKeys.DRIPSTONE_CAVES,
            BiomeKeys.DEEP_DARK,
                BiomeKeys.SNOWY_SLOPES,
                BiomeKeys.STONY_PEAKS,
                BiomeKeys.GROVE,
                BiomeKeys.WINDSWEPT_HILLS,
                BiomeKeys.SAVANNA_PLATEAU,
                BiomeKeys.SNOWY_PLAINS,
                BiomeKeys.MANGROVE_SWAMP,
                BiomeKeys.SNOWY_TAIGA,
                BiomeKeys.JUNGLE,
                BiomeKeys.BADLANDS
        );
        if (!keys.contains(BiomeKeys.PLAINS)) {
            keys.add(BiomeKeys.PLAINS);
        }
        return Collections.unmodifiableSet(keys);
    }


}
