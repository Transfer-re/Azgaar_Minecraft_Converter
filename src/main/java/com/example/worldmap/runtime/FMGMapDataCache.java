package com.example.worldmap.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.fmg.FMGHeightSampler;
import com.example.fmg.FMGMapData;
import com.example.fmg.FMGMapLoader;
import com.example.fmg.FMGRouteSampler;

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

        Path resolved = resolveMapJson(rawPath);
        return CACHE.computeIfAbsent(resolved, FMGMapDataCache::read);
    }

    /**
     * Resolve the JSON file to actually load, preferring the copy inside the
     * world folder ({@code <world>/fmg/map.json}) so a saved world is
     * self-contained. On the first load of a world whose local copy is missing,
     * the source JSON is copied in.
     */
    private static Path resolveMapJson(String rawPath) {
        Path fmgDir = com.example.worldgen.FmgWorldSettings.fmgDir();
        Path localCopy = fmgDir == null ? null : fmgDir.resolve("map.json");

        if (localCopy != null && Files.isRegularFile(localCopy)) {
            return localCopy.normalize();
        }

        // Inside a running FMG world the per-world MapInfo path wins; the global
        // config.json mapJsonPath override is only a fallback for tooling.
        Path source = fmgDir != null
                ? resolve(rawPath)
                : resolveConfiguredOverrideOrDefault(resolve(rawPath));

        if (localCopy != null && Files.isRegularFile(source)) {
            try {
                Files.createDirectories(fmgDir);
                Files.copy(source, localCopy);
                LOGGER.info("Copied FMG export into world folder: {}", localCopy);
                return localCopy.normalize();
            } catch (IOException ex) {
                LOGGER.warn("Could not copy FMG export into the world folder (using {} directly)", source, ex);
            }
        }
        return source;
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
            // Blended-terrain worlds: the baked raster, or nothing yet -- FmgBake
            // builds it right after this load (the legacy field would be wasted work).
            if (com.example.worldgen.FmgBake.applyBakedTerrain(data)) {
                return data;
            }
            com.example.worldmap.MapInfo current = com.example.worldgen.FmgWorldSettings.current();
            if (current != null && current.blendTerrain()) {
                return data;
            }
            // Prefer a persisted bake for the (slow) heightfield; otherwise build
            // it now on the world-load thread rather than on a gen worker.
            if (!com.example.worldgen.FmgBake.applyBakedHeightField(data)) {
                FMGHeightSampler.sampleHeight(data, 0, 0);
            }

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
