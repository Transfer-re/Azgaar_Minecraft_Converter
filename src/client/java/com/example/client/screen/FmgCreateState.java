package com.example.client.screen;

import java.util.Map;
import java.util.WeakHashMap;

import com.example.worldgen.ImageMapChunkGenerator;
import com.example.worldmap.MapInfo;

import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.gen.WorldPreset;

/**
 * Tracks, per create-world session, whether the user has actually picked an FMG
 * map file through {@link FmgWorldCreateScreen}. The "Fantasy Map" preset ships
 * with a default {@link MapInfo}, so the generator alone can't tell us -- we
 * remember the exact instance we applied and compare by identity. Re-selecting
 * a world type rebuilds the generator from the preset, which drops that
 * instance and correctly puts the creator back into "no file chosen".
 */
public final class FmgCreateState {

    private static final Map<WorldCreator, MapInfo> APPLIED = new WeakHashMap<>();

    private FmgCreateState() {
    }

    public static void markApplied(WorldCreator creator, MapInfo mapInfo) {
        APPLIED.put(creator, mapInfo);
    }

    /** True when the Fantasy Map world type is selected but no map file has been chosen yet. */
    public static boolean needsMapFile(WorldCreator creator) {
        RegistryEntry<WorldPreset> preset = creator.getWorldType().preset();
        if (preset == null || !preset.matchesKey(FmgWorldCreateScreen.FMG_PRESET_KEY)) {
            return false;
        }
        MapInfo applied = APPLIED.get(creator);
        return !(applied != null
                && creator.getGeneratorOptionsHolder().selectedDimensions().getChunkGenerator()
                        instanceof ImageMapChunkGenerator generator
                && generator.mapInfo() == applied);
    }
}
