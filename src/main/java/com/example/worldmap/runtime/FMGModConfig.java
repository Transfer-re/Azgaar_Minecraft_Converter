package com.example.worldmap.runtime;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Global mod config.
 *
 * File location: config/fantasymapgenerator/config.json
 */
final class FMGModConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("FMGModConfig");

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final String CONFIG_RELATIVE_PATH = "fantasymapgenerator/config.json";

        /**
         * Optional override for the FMG export JSON file.
         *
         * If empty, the map specified by the dimension's MapInfo ("fmg_export") is used.
         *
         * If relative, it's resolved against the Minecraft instance directory.
         * Examples:
         * - "Boisia Full 2025-12-26-21-37.json"
         * - "maps/Boisia Full 2025-12-26-21-37.json"
         * - "C:/Users/you/Downloads/Boisia.json"
         */
        record Config(String mapJsonPath) {}

    static Config loadOrCreate() {
        Config defaults = defaultConfig();

        Path configPath;
        try {
            configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_RELATIVE_PATH).normalize();
        } catch (Throwable t) {
            LOGGER.warn("FabricLoader config dir unavailable; using built-in config", t);
            return defaults;
        }

        if (!Files.exists(configPath)) {
            try {
                writeConfig(configPath, defaults);
            } catch (IOException ex) {
                LOGGER.warn("Failed to write default config at {} (using defaults)", configPath, ex);
            }
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            Config loaded = GSON.fromJson(reader, Config.class);
            if (loaded == null) {
                return defaults;
            }
            String path = loaded.mapJsonPath();
            if (path == null) {
                path = defaults.mapJsonPath();
            }
            return new Config(path);
        } catch (Exception ex) {
            LOGGER.warn("Failed to read config (using defaults)", ex);
            return defaults;
        }
    }

    private static void writeConfig(Path path, Config config) throws IOException {
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        }
    }

    private static Config defaultConfig() {
        return new Config("");
    }
}
