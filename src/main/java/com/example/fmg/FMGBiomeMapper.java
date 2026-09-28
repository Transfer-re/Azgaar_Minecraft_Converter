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
 *
 * <p>The primary path is {@link FMGBiomeVariantMap}: a precomputed, per-cell
 * assignment that picks a rich vanilla variant per contiguous Azgaar-named
 * region (see that class for why). This class resolves the final
 * {@link RegistryEntry} from the {@link RegistryKey} it returns, and provides
 * the plain 1:1 name mapping ({@link #legacyKeyFor}) as the fallback for any
 * cell the variant map didn't cover (should be rare -- essentially never,
 * since the variant map assigns every cell it sees).
 */
public final class FMGBiomeMapper {

    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]");
    private static final Map<String, RegistryKey<Biome>> NAMED_MAPPINGS = FMGBiomeMappingConfig.loadOrCreateMappings();

    private FMGBiomeMapper() {}

    // ===== Public API =====

    /** Every biome this profile's mapping can produce, for the biome source to declare. */
    public static Set<RegistryKey<Biome>> paletteKeys(com.example.profile.FmgContentProfile profile) {
        return buildPaletteKeys(profile);
    }

    /** Lowercase alnum-only key -- shared by {@link FMGBiomeVariantMap} and the content profile. */
    static String normalize(String rawName) {
        if (rawName == null || rawName.isBlank()) return "";
        return NON_WORD.matcher(rawName.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /** The plain 1:1 name mapping, with no variants -- the fallback when the variant map has nothing for a cell. */
    static RegistryKey<Biome> legacyKeyFor(String normalizedName) {
        return NAMED_MAPPINGS.get(normalizedName);
    }

    public static RegistryEntry<Biome> resolve(
            String fmgExport,
            boolean richBiomes,
            com.example.profile.FmgContentProfile profile,
            RegistryEntryLookup<Biome> biomes,
            FMGMapData mapData,
            int worldX,
            int worldZ
    ) {
        return resolve(fmgExport, richBiomes, profile, key -> lookup(biomes, key), mapData, worldX, worldZ);
    }

    public static RegistryEntry<Biome> resolve(
            String fmgExport,
            boolean richBiomes,
            com.example.profile.FmgContentProfile profile,
            Function<RegistryKey<Biome>, RegistryEntry<Biome>> resolver,
            FMGMapData mapData,
            int worldX,
            int worldZ
    ) {
        if (mapData == null || mapData.getInfo() == null) {
            return lookup(resolver, BiomeKeys.PLAINS);
        }

        double scale = FMGHeightSampler.sampleScale();
        double offsetX = -(mapData.getInfo().getWidth() * scale) / 2.0;
        double offsetZ = -(mapData.getInfo().getHeight() * scale) / 2.0;

        double fmgX = (worldX - offsetX) / scale;
        double fmgY = (worldZ - offsetZ) / scale;

        FMGCell cell = mapData.findNearestCell(fmgX, fmgY);
        return resolve(fmgExport, richBiomes, profile, resolver, mapData, cell);
    }

    public static RegistryEntry<Biome> resolve(
            String fmgExport,
            boolean richBiomes,
            com.example.profile.FmgContentProfile profile,
            Function<RegistryKey<Biome>, RegistryEntry<Biome>> resolver,
            FMGMapData mapData,
            FMGCell cell
    ) {
        if (cell == null) {
            return lookup(resolver, BiomeKeys.PLAINS);
        }

        RegistryKey<Biome> key = richBiomes
                ? FMGBiomeVariantMap.get(fmgExport, mapData, profile).keyFor(cell)
                : null;
        if (key == null) {
            FMGBiome biomeDef = mapData.getBiome(cell.getBiome());
            key = biomeDef != null ? mapByName(normalize(biomeDef.getName()), cell) : null;
        }
        return lookup(resolver, key != null ? key : BiomeKeys.PLAINS);
    }

    /**
     * The land biome key for a block column, with the profile's
     * {@code biomeEdgeWarp} applied -- the same lookup the biome source makes, so
     * the layered surface picks its palette where the biome actually changes.
     * Never the river/beach override: those take the land biome's palette.
     */
    public static RegistryKey<Biome> resolveKey(
            String fmgExport,
            boolean richBiomes,
            com.example.profile.FmgContentProfile profile,
            FMGMapData mapData,
            int worldX,
            int worldZ
    ) {
        if (mapData == null || mapData.getInfo() == null) {
            return BiomeKeys.PLAINS;
        }
        int[] w = warped(profile, worldX, worldZ);
        double scale = FMGHeightSampler.sampleScale();
        double offsetX = -(mapData.getInfo().getWidth() * scale) / 2.0;
        double offsetZ = -(mapData.getInfo().getHeight() * scale) / 2.0;
        FMGCell cell = mapData.findNearestCell((w[0] - offsetX) / scale, (w[1] - offsetZ) / scale);
        if (cell == null) {
            return BiomeKeys.PLAINS;
        }
        RegistryKey<Biome> key = richBiomes
                ? FMGBiomeVariantMap.get(fmgExport, mapData, profile).keyFor(cell)
                : null;
        if (key == null) {
            FMGBiome biomeDef = mapData.getBiome(cell.getBiome());
            key = biomeDef != null ? mapByName(normalize(biomeDef.getName()), cell) : null;
        }
        return key != null ? key : BiomeKeys.PLAINS;
    }

    /** Block position displaced by the profile's biome-edge warp (unchanged when it is 0). */
    public static int[] warped(com.example.profile.FmgContentProfile profile, int worldX, int worldZ) {
        if (profile == null || profile.biomeEdgeWarp <= 0.0) {
            return new int[] {worldX, worldZ};
        }
        double[] d = com.example.surface.SurfaceNoise.warp(profile.biomeEdgeWarp, worldX, worldZ);
        return new int[] {worldX + (int) Math.round(d[0]), worldZ + (int) Math.round(d[1])};
    }

    // ===== Internals =====

    /** Last-resort fallback when even the variant map has nothing -- the old heuristics, unchanged. */
    private static RegistryKey<Biome> mapByName(String normalized, FMGCell cell) {
        if (normalized.isEmpty()) {
            return null;
        }

        RegistryKey<Biome> key = legacyKeyFor(normalized);
        if (key != null) {
            return key;
        }

        if (normalized.contains("mount")) {
            return BiomeKeys.STONY_PEAKS;
        }
        if (normalized.contains("swamp") || normalized.contains("wet")) {
            return BiomeKeys.SWAMP;
        }
        if (normalized.contains("forest")) {
            return BiomeKeys.FOREST;
        }
        if (normalized.contains("tundra") || normalized.contains("glacier")) {
            return BiomeKeys.SNOWY_TAIGA;
        }
        if (normalized.contains("steppe")) {
            return BiomeKeys.WINDSWEPT_SAVANNA;
        }
        if (cell != null && cell.getH() < 20) {
            return BiomeKeys.MANGROVE_SWAMP;
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

    private static Set<RegistryKey<Biome>> buildPaletteKeys(com.example.profile.FmgContentProfile profile) {
        Set<RegistryKey<Biome>> keys = new LinkedHashSet<>(NAMED_MAPPINGS.values());
        keys.addAll(profile.allBiomeKeys());
        Collections.addAll(
                keys,
                BiomeKeys.DEEP_OCEAN,
                BiomeKeys.OCEAN,
                BiomeKeys.BEACH,
                BiomeKeys.LUSH_CAVES,
                BiomeKeys.DRIPSTONE_CAVES,
                BiomeKeys.DEEP_DARK
        );
        if (!keys.contains(BiomeKeys.PLAINS)) {
            keys.add(BiomeKeys.PLAINS);
        }
        return Collections.unmodifiableSet(keys);
    }
}
