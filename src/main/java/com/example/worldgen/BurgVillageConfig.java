package com.example.worldgen;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Loads/saves config for selecting a village "type" by biome.
 *
 * File location: config/fantasymapgenerator/villages.json
 */
final class BurgVillageConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("BurgVillageConfig");

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final String CONFIG_RELATIVE_PATH = "fantasymapgenerator/villages.json";

    private static final int CURRENT_VERSION = 3;

    record VillageType(String startPool, String startJigsawName, int size) {}

    record Config(
            int version,
            int radiusMin,
            int radiusMax,
            int radiusBase,
            int populationDivisor,
            List<String> defaultVillageTypes,
            Map<String, VillageType> villageTypes,
            Map<String, List<String>> biomeVillageTypes
    ) {}

    static Config loadOrCreate() {
        Config defaults = defaultConfig();

        Path configPath;
        try {
            configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_RELATIVE_PATH).normalize();
        } catch (Throwable t) {
            LOGGER.warn("FabricLoader config dir unavailable; using built-in village config", t);
            return defaults;
        }

        if (!Files.exists(configPath)) {
            try {
                writeConfig(configPath, defaults);
            } catch (IOException ex) {
                LOGGER.warn("Failed to write default villages config at {} (using defaults)", configPath, ex);
            }
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            Config loaded = GSON.fromJson(reader, Config.class);
            if (loaded == null) {
                return defaults;
            }
            // If the JSON is missing newer fields, fall back to defaults for safety.
            Config merged = merge(defaults, loaded);
            if (loaded.version() < defaults.version()) {
                try {
                    writeConfig(configPath, merged);
                } catch (IOException ex) {
                    LOGGER.warn("Failed to migrate villages config at {} (continuing with merged config)", configPath, ex);
                }
            }
            return merged;
        } catch (Exception ex) {
            LOGGER.warn("Failed to read villages config (using defaults)", ex);
            return defaults;
        }
    }

    private static Config merge(Config defaults, Config loaded) {
        List<String> mergedDefaultVillageTypes = pickDefaultVillageTypes(defaults, loaded);

        Map<String, VillageType> mergedVillageTypes = new LinkedHashMap<>(defaults.villageTypes());
        if (loaded.villageTypes() != null && !loaded.villageTypes().isEmpty()) {
            mergedVillageTypes.putAll(loaded.villageTypes());
        }

        Map<String, List<String>> mergedBiomeVillageTypes = new LinkedHashMap<>(defaults.biomeVillageTypes());
        if (loaded.biomeVillageTypes() != null && !loaded.biomeVillageTypes().isEmpty()) {
            boolean collapseToSingle = loaded.version() < CURRENT_VERSION;
            mergedBiomeVillageTypes.putAll(normalizeBiomeKeys(loaded.biomeVillageTypes(), collapseToSingle));
        }

        return new Config(
                defaults.version(),
                loaded.radiusMin() != 0 ? loaded.radiusMin() : defaults.radiusMin(),
                loaded.radiusMax() != 0 ? loaded.radiusMax() : defaults.radiusMax(),
                loaded.radiusBase() != 0 ? loaded.radiusBase() : defaults.radiusBase(),
                loaded.populationDivisor() != 0 ? loaded.populationDivisor() : defaults.populationDivisor(),
                mergedDefaultVillageTypes,
                Collections.unmodifiableMap(mergedVillageTypes),
                Collections.unmodifiableMap(mergedBiomeVillageTypes)
        );
    }

    private static List<String> pickDefaultVillageTypes(Config defaults, Config loaded) {
        List<String> types = loaded.defaultVillageTypes();
        if (types == null || types.isEmpty()) {
            return defaults.defaultVillageTypes();
        }

        // v3: We no longer support weighted default blends in the built-in defaults.
        // If an older config has multiple defaults, migrate to the new single-default behavior.
        if (loaded.version() < CURRENT_VERSION && types.size() > 1) {
            return defaults.defaultVillageTypes();
        }

        // Migration helper: older configs were generated with ["plains"]. If the config has no
        // version field, allow the new defaults to take effect without forcing manual deletion.
        if (loaded.version() <= 0
                && types.size() == 1
                && "plains".equalsIgnoreCase(types.getFirst())) {
            return defaults.defaultVillageTypes();
        }

        return types;
    }

    private static Map<String, List<String>> normalizeBiomeKeys(Map<String, List<String>> in, boolean collapseToSingle) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : in.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }

            List<String> types = e.getValue();
            if (collapseToSingle && types.size() > 1) {
                // v3 migration: collapse any weighted lists to their first entry.
                types = List.of(types.getFirst());
            }

            out.put(e.getKey().toLowerCase(Locale.ROOT), types);
        }
        return out;
    }

    private static void writeConfig(Path path, Config config) throws IOException {
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        }
    }

    private static Config defaultConfig() {
        Map<String, VillageType> types = new LinkedHashMap<>();
        types.put("plains", new VillageType("minecraft:village/plains/town_centers", "", 3));
        types.put("desert", new VillageType("minecraft:village/desert/town_centers", "", 3));
        types.put("savanna", new VillageType("minecraft:village/savanna/town_centers", "", 3));
        types.put("snowy", new VillageType("minecraft:village/snowy/town_centers", "", 3));
        types.put("taiga", new VillageType("minecraft:village/taiga/town_centers", "", 3));
        // Kept for backward compatibility with existing configs, but sized the same as other types.
        types.put("taiga_large", new VillageType("minecraft:village/taiga/town_centers", "", 3));

        Map<String, List<String>> biomeMap = new LinkedHashMap<>();
        // ===== Temperate =====
        biomeMap.put("minecraft:plains", List.of("plains"));
        biomeMap.put("minecraft:sunflower_plains", List.of("plains"));
        biomeMap.put("minecraft:meadow", List.of("plains"));
        biomeMap.put("minecraft:forest", List.of("plains"));
        biomeMap.put("minecraft:flower_forest", List.of("plains"));
        biomeMap.put("minecraft:birch_forest", List.of("plains"));
        biomeMap.put("minecraft:old_growth_birch_forest", List.of("plains"));
        biomeMap.put("minecraft:dark_forest", List.of("plains"));
        biomeMap.put("minecraft:cherry_grove", List.of("plains"));

        // Windswept / hilly: still plains villages.
        biomeMap.put("minecraft:windswept_hills", List.of("plains"));
        biomeMap.put("minecraft:windswept_forest", List.of("plains"));
        biomeMap.put("minecraft:windswept_gravelly_hills", List.of("plains"));

        // ===== Hot & dry =====
        biomeMap.put("minecraft:desert", List.of("desert"));
        biomeMap.put("minecraft:badlands", List.of("desert"));

        biomeMap.put("minecraft:savanna", List.of("savanna"));
        biomeMap.put("minecraft:savanna_plateau", List.of("savanna"));
        biomeMap.put("minecraft:windswept_savanna", List.of("savanna"));

        // ===== Cold / snowy =====
        biomeMap.put("minecraft:snowy_plains", List.of("snowy"));
        biomeMap.put("minecraft:ice_spikes", List.of("snowy"));
        biomeMap.put("minecraft:snowy_slopes", List.of("snowy"));
        biomeMap.put("minecraft:frozen_peaks", List.of("snowy"));
        biomeMap.put("minecraft:jagged_peaks", List.of("snowy"));
        biomeMap.put("minecraft:grove", List.of("snowy"));

        // ===== Taiga =====
        biomeMap.put("minecraft:taiga", List.of("taiga"));
        biomeMap.put("minecraft:snowy_taiga", List.of("taiga"));
        biomeMap.put("minecraft:old_growth_pine_taiga", List.of("taiga"));
        biomeMap.put("minecraft:old_growth_spruce_taiga", List.of("taiga"));

        // Jungle: no vanilla jungle village; use plains as the closest general-purpose type.
        biomeMap.put("minecraft:jungle", List.of("plains"));
        biomeMap.put("minecraft:sparse_jungle", List.of("plains"));
        biomeMap.put("minecraft:bamboo_jungle", List.of("plains"));

        // Wetlands: no vanilla swamp village; use plains.
        biomeMap.put("minecraft:swamp", List.of("plains"));
        biomeMap.put("minecraft:mangrove_swamp", List.of("plains"));

        // ===== FMG biome keys =====
        // These keys allow village selection to depend on the FantasyMapGenerator biome name
        // even if the visual Minecraft biome mapping is something else (e.g. cold desert -> dark forest).
        biomeMap.put("fmg:grassland", List.of("plains"));
        biomeMap.put("fmg:temperatedeciduousforest", List.of("plains"));
        biomeMap.put("fmg:temperaterainforest", List.of("plains"));
        biomeMap.put("fmg:taiga", List.of("taiga"));

        // Hot & dry
        biomeMap.put("fmg:hotdesert", List.of("desert"));
        biomeMap.put("fmg:savanna", List.of("savanna"));

        // Cold & wet/cold & dry
        biomeMap.put("fmg:colddesert", List.of("desert"));
        biomeMap.put("fmg:tundra", List.of("snowy"));
        biomeMap.put("fmg:glacier", List.of("snowy"));
        biomeMap.put("fmg:wetland", List.of("plains"));
        biomeMap.put("fmg:swamp", List.of("plains"));
        biomeMap.put("fmg:marsh", List.of("plains"));

        // Tropical / jungle-ish: use a bigger taiga-style village as a stand-in.
        biomeMap.put("fmg:jungle", List.of("plains"));
        biomeMap.put("fmg:rainforest", List.of("plains"));
        biomeMap.put("fmg:tropicalseasonalforest", List.of("plains"));
        biomeMap.put("fmg:tropicalrainforest", List.of("plains"));

        return new Config(
            CURRENT_VERSION,
                32,
                160,
                24,
                8,
            List.of("plains"),
                Collections.unmodifiableMap(types),
                Collections.unmodifiableMap(biomeMap)
        );
    }
}
