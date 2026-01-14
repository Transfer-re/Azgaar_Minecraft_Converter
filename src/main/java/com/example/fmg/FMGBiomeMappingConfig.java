package com.example.fmg;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;

/**
 * Loads/saves the FMG biome name -> biome registry id mapping.
 *
 * File location: config/fantasymapgenerator/biome_mappings.json
 */
final class FMGBiomeMappingConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("FMGBiomeMappingConfig");

    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]");

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final String CONFIG_RELATIVE_PATH = "fantasymapgenerator/biome_mappings.json";

    private FMGBiomeMappingConfig() {
    }

    static Map<String, RegistryKey<Biome>> loadOrCreateMappings() {
        Map<String, String> defaults = defaultMappingsAsString();

        Path configPath;
        try {
            configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_RELATIVE_PATH).normalize();
        } catch (Throwable t) {
            // In case this code is ever executed outside a Fabric runtime.
            LOGGER.warn("FabricLoader config dir unavailable; using built-in biome mappings", t);
            return toRegistryKeys(defaults);
        }

        if (!Files.exists(configPath)) {
            try {
                writeConfig(configPath, defaults);
            } catch (IOException ex) {
                LOGGER.warn("Failed to write default biome mapping config at {} (using defaults)", configPath, ex);
                return toRegistryKeys(defaults);
            }
            return toRegistryKeys(defaults);
        }

        try {
            Map<String, String> loaded = readConfig(configPath);
            if (loaded.isEmpty()) {
                return toRegistryKeys(defaults);
            }

            // Merge loaded on top of defaults so missing keys still exist.
            Map<String, String> merged = new LinkedHashMap<>(defaults);
            merged.putAll(loaded);
            return toRegistryKeys(merged);
        } catch (Exception ex) {
            LOGGER.warn("Failed to read biome mapping config at {} (using defaults)", configPath, ex);
            return toRegistryKeys(defaults);
        }
    }

    private static Map<String, String> readConfig(Path configPath) throws IOException {
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            if (root == null || !root.isJsonObject()) {
                return Collections.emptyMap();
            }

            JsonObject obj = root.getAsJsonObject();
            JsonObject mappings = obj.has("mappings") && obj.get("mappings").isJsonObject()
                    ? obj.getAsJsonObject("mappings")
                    : obj;

            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : mappings.entrySet()) {
                if (entry.getValue() == null || !entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                String key = normalize(entry.getKey());
                String value = entry.getValue().getAsString();
                if (key.isEmpty() || value == null || value.isBlank()) {
                    continue;
                }
                out.put(key, value);
            }
            return out;
        }
    }

    private static void writeConfig(Path configPath, Map<String, String> mappings) throws IOException {
        Files.createDirectories(configPath.getParent());

        JsonObject root = new JsonObject();
        root.addProperty("version", 1);

        JsonObject mapObj = new JsonObject();
        for (Map.Entry<String, String> e : mappings.entrySet()) {
            mapObj.addProperty(e.getKey(), e.getValue());
        }
        root.add("mappings", mapObj);

        try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
    }

    private static Map<String, RegistryKey<Biome>> toRegistryKeys(Map<String, String> mappings) {
        Map<String, RegistryKey<Biome>> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : mappings.entrySet()) {
            String key = normalize(e.getKey());
            if (key.isEmpty()) {
                continue;
            }

            RegistryKey<Biome> biomeKey = parseBiomeKey(e.getValue());
            if (biomeKey != null) {
                out.put(key, biomeKey);
            }
        }
        return out;
    }

    private static RegistryKey<Biome> parseBiomeKey(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }

        try {
            Identifier id = Identifier.of(rawId.trim());
            return RegistryKey.of(RegistryKeys.BIOME, id);
        } catch (Exception ex) {
            LOGGER.warn("Invalid biome id '{}' in biome mapping config (skipping)", rawId, ex);
            return null;
        }
    }

    private static String normalize(String rawName) {
        if (rawName == null || rawName.isEmpty()) {
            return "";
        }
        return NON_WORD.matcher(rawName.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /**
     * Defaults that mirror the current hardcoded mappings.
     *
     * Keys are already normalized (lowercase alnum).
     */
    private static Map<String, String> defaultMappingsAsString() {
        Map<String, String> map = new LinkedHashMap<>();

        // Core FMG biome categories from the export
        map.put("marine", BiomeKeys.OCEAN.getValue().toString());
        map.put("hotdesert", BiomeKeys.DESERT.getValue().toString());
        map.put("colddesert", BiomeKeys.DARK_FOREST.getValue().toString());
        map.put("savanna", BiomeKeys.SAVANNA.getValue().toString());
        map.put("grassland", BiomeKeys.PLAINS.getValue().toString());
        map.put("tropicalseasonalforest", BiomeKeys.JUNGLE.getValue().toString());
        map.put("temperatedeciduousforest", BiomeKeys.FOREST.getValue().toString());
        map.put("tropicalrainforest", BiomeKeys.JUNGLE.getValue().toString());
        map.put("temperaterainforest", BiomeKeys.FOREST.getValue().toString());
        map.put("taiga", BiomeKeys.TAIGA.getValue().toString());
        map.put("tundra", BiomeKeys.SNOWY_PLAINS.getValue().toString());
        map.put("glacier", BiomeKeys.SNOWY_SLOPES.getValue().toString());
        map.put("wetland", BiomeKeys.MANGROVE_SWAMP.getValue().toString());

        // Generic aliases / fallbacks
        map.put("plains", BiomeKeys.PLAINS.getValue().toString());
        map.put("desert", BiomeKeys.DESERT.getValue().toString());
        map.put("forest", BiomeKeys.FOREST.getValue().toString());
        map.put("steppe", BiomeKeys.WINDSWEPT_SAVANNA.getValue().toString());
        map.put("swamp", BiomeKeys.SWAMP.getValue().toString());
        map.put("marsh", BiomeKeys.SWAMP.getValue().toString());
        map.put("jungle", BiomeKeys.JUNGLE.getValue().toString());
        map.put("rainforest", BiomeKeys.JUNGLE.getValue().toString());
        map.put("mountain", BiomeKeys.STONY_PEAKS.getValue().toString());
        map.put("highlands", BiomeKeys.WINDSWEPT_HILLS.getValue().toString());
        map.put("mesa", BiomeKeys.BADLANDS.getValue().toString());
        map.put("ocean", BiomeKeys.OCEAN.getValue().toString());
        map.put("coast", BiomeKeys.BEACH.getValue().toString());

        return map;
    }
}
