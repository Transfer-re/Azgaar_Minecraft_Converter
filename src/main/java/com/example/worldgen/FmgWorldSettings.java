package com.example.worldgen;

import java.nio.file.Path;

import com.example.worldmap.MapInfo;

/**
 * Process-wide holder for the currently-loaded FMG world's per-world settings.
 *
 * <p>There is at most one FMG overworld per running server, so a static slot is
 * enough. {@code current} is set from {@link ImageMapChunkGenerator}'s
 * constructor (which runs on world load with the deserialised {@link MapInfo});
 * {@code saveDir} is set from {@code SERVER_STARTING}. Static accessors like
 * {@code FMGHeightSampler.sampleScale()} read from here and fall back to the
 * global config file when nothing is loaded (data-gen, tooling, tests).</p>
 */
public final class FmgWorldSettings {

    private static volatile MapInfo current;
    private static volatile Path saveDir;

    private FmgWorldSettings() {
    }

    public static void setCurrent(MapInfo mapInfo) {
        current = mapInfo;
    }

    /** @return the loaded world's MapInfo, or {@code null} when no FMG world is active. */
    public static MapInfo current() {
        return current;
    }

    public static void setSaveDir(Path dir) {
        saveDir = dir;
    }

    /** @return {@code <world>/fmg} for the running server, or {@code null} if not started. */
    public static Path fmgDir() {
        Path d = saveDir;
        return d == null ? null : d.resolve("fmg");
    }

    public static void clear() {
        current = null;
        saveDir = null;
    }
}
