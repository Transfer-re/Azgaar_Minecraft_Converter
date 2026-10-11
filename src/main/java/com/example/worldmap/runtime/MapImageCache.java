package com.example.worldmap.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.worldmap.MapInfo;

/**
 * Global cache mapping map definitions to FMG map data.
 *
 * <p>Keyed by the whole {@link MapInfo}, not just the export path: two worlds on
 * the same map can differ in profile, scale or terrain, and a path key handed the
 * second world the first one's settings for the rest of the session. The parsed
 * map itself is still shared per export through {@link FMGMapDataCache}.
 */
public final class MapImageCache {

    private static final Map<MapInfo, MapImageData> CACHE = new ConcurrentHashMap<>();

    private MapImageCache() {
    }

    public static MapImageData get(MapInfo info) {
        return CACHE.computeIfAbsent(info, MapImageData::from);
    }

    public static void invalidate() {
        CACHE.clear();
        FMGMapDataCache.invalidate();
    }
}
