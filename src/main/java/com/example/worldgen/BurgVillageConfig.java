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

    record VillageType(String startPool, String startJigsawName, int size) {}

    record Config(
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
            return merge(defaults, loaded);
        } catch (Exception ex) {
            LOGGER.warn("Failed to read villages config (using defaults)", ex);
            return defaults;
        }
    }

    private static Config merge(Config defaults, Config loaded) {
        return new Config(
                loaded.radiusMin() != 0 ? loaded.radiusMin() : defaults.radiusMin(),
                loaded.radiusMax() != 0 ? loaded.radiusMax() : defaults.radiusMax(),
                loaded.radiusBase() != 0 ? loaded.radiusBase() : defaults.radiusBase(),
                loaded.populationDivisor() != 0 ? loaded.populationDivisor() : defaults.populationDivisor(),
                loaded.defaultVillageTypes() != null && !loaded.defaultVillageTypes().isEmpty()
                        ? loaded.defaultVillageTypes()
                        : defaults.defaultVillageTypes(),
                loaded.villageTypes() != null && !loaded.villageTypes().isEmpty()
                        ? loaded.villageTypes()
                        : defaults.villageTypes(),
                loaded.biomeVillageTypes() != null && !loaded.biomeVillageTypes().isEmpty()
                        ? normalizeBiomeKeys(loaded.biomeVillageTypes())
                        : defaults.biomeVillageTypes()
        );
    }

    private static Map<String, List<String>> normalizeBiomeKeys(Map<String, List<String>> in) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : in.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
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
        types.put("plains", new VillageType("minecraft:village/plains/town_centers", "", 6));
        types.put("desert", new VillageType("minecraft:village/desert/town_centers", "", 6));
        types.put("savanna", new VillageType("minecraft:village/savanna/town_centers", "", 6));
        types.put("snowy", new VillageType("minecraft:village/snowy/town_centers", "", 6));
        types.put("taiga", new VillageType("minecraft:village/taiga/town_centers", "", 6));

        Map<String, List<String>> biomeMap = new LinkedHashMap<>();
        biomeMap.put("minecraft:plains", List.of("plains"));
        biomeMap.put("minecraft:sunflower_plains", List.of("plains"));
        biomeMap.put("minecraft:meadow", List.of("plains"));

        biomeMap.put("minecraft:desert", List.of("desert"));
        biomeMap.put("minecraft:badlands", List.of("desert"));

        biomeMap.put("minecraft:savanna", List.of("savanna"));
        biomeMap.put("minecraft:savanna_plateau", List.of("savanna"));

        biomeMap.put("minecraft:snowy_plains", List.of("snowy"));
        biomeMap.put("minecraft:ice_spikes", List.of("snowy"));

        biomeMap.put("minecraft:taiga", List.of("taiga"));
        biomeMap.put("minecraft:snowy_taiga", List.of("taiga"));
        biomeMap.put("minecraft:old_growth_pine_taiga", List.of("taiga"));
        biomeMap.put("minecraft:old_growth_spruce_taiga", List.of("taiga"));

        return new Config(
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
