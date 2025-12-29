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

    private FMGMapDataCache() {
    }

    public static FMGMapData load(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("fmg_export must be defined");
        }
        Path path = resolve(rawPath);
        return CACHE.computeIfAbsent(path, FMGMapDataCache::read);
    }

    public static void invalidate() {
        CACHE.clear();
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
