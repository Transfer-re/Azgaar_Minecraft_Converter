package com.example.worldmap.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.fmg.FMGMapData;
import com.example.fmg.FMGMapLoader;
import com.example.fmg.FMGRouteSampler;
import com.example.fmg.FMRiverSampler;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Loads FMG JSON exports from disk and keeps them cached for sampling.
 */
public final class FMGMapDataCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("FMGMapDataCache");
    private static final Map<Path, FMGMapData> CACHE = new ConcurrentHashMap<>();

    // Loaded lazily to avoid touching FabricLoader config dir too early.
    private static volatile FMGModConfig.Config CONFIG;

    private FMGMapDataCache() {
    }

    public static FMGMapData load(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("fmg_export must be defined");
        }

        Path defaultPath = resolve(rawPath);
        Path resolved = resolveConfiguredOverrideOrDefault(defaultPath);
        return CACHE.computeIfAbsent(resolved, FMGMapDataCache::read);
    }

    public static void invalidate() {
        CACHE.clear();
        CONFIG = null;
    }

    /**
     * FMG-pixels to world-blocks scale factor.
     *
     * <p>This value is read from {@code config/fantasymapgenerator/config.json}
     * as {@code sampleScale}. If missing/invalid, defaults are used.</p>
     */
    public static double sampleScale() {
        FMGModConfig.Config config = getConfig();
        if (config == null) {
            return FMGModConfig.DEFAULT_SAMPLE_SCALE;
        }

        Double s = config.sampleScale();
        if (s == null || !Double.isFinite(s) || s <= 0.0) {
            return FMGModConfig.DEFAULT_SAMPLE_SCALE;
        }
        return s;
    }

    private static Path resolveConfiguredOverrideOrDefault(Path defaultPath) {
        FMGModConfig.Config config = getConfig();
        if (config == null) {
            return defaultPath;
        }
        String override = config.mapJsonPath();
        if (override == null || override.isBlank()) {
            return defaultPath;
        }

        Path overridePath = resolve(override);
        if (!Files.exists(overridePath)) {
            LOGGER.warn("Configured mapJsonPath does not exist: {} (falling back to {})", overridePath, defaultPath);
            return defaultPath;
        }
        return overridePath;
    }

    private static FMGModConfig.Config getConfig() {
        FMGModConfig.Config local = CONFIG;
        if (local != null) {
            return local;
        }
        synchronized (FMGMapDataCache.class) {
            if (CONFIG == null) {
                CONFIG = FMGModConfig.loadOrCreate();
            }
            return CONFIG;
        }
    }

    private static FMGMapData read(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalStateException("Missing FMG export at " + path);
        }
        try {
            FMGMapData data = FMGMapLoader.loadMap(path);

            FMGRouteSampler.build(data);
            FMRiverSampler.build(data);

            return data;
        } catch (IOException ex) {
            LOGGER.error("Failed to load FMG export {}", path, ex);
            throw new IllegalStateException("Failed to load FMG export at " + path, ex);
        }
    }

    private static Path resolve(String rawPath) {
        Path path = Path.of(rawPath.replace("\\", "/"));
        if (!path.isAbsolute()) {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            path = gameDir.resolve(path);
        }
        return path.normalize();
    }
}
