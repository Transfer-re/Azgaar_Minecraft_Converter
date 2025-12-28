package com.example.worldmap.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.worldmap.MapInfo;

/**
 * Global cache mapping map definitions to FMG map data.
 */
public final class MapImageCache {

    private static final Map<String, MapImageData> CACHE = new ConcurrentHashMap<>();

    private MapImageCache() {
    }

    public static MapImageData get(MapInfo info) {
        return CACHE.computeIfAbsent(info.fmgExport(), path -> MapImageData.from(info));
    }

    public static void invalidate() {
        CACHE.clear();
        FMGMapDataCache.invalidate();
    }
}
