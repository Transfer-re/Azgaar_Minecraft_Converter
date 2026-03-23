package com.example.worldgen;

import java.util.Locale;
import java.util.regex.Pattern;

import com.example.fmg.FMGBiome;
import com.example.fmg.FMGCell;
import com.example.fmg.FMGHeightSampler;
import com.example.fmg.FMGMapData;

/**
 * Computes a normalized FMG biome key (lowercase alnum) for a world position.
 *
 * <p>This is intentionally separate from vanilla biome resolution so features (like villages)
 * can be configured by the FMG map's biome categories even when multiple FMG biomes map to the
 * same vanilla biome for visuals.
 */
final class FmgBiomeKeySampler {

    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]");

    private FmgBiomeKeySampler() {
    }

    static String sampleNormalizedKey(FMGMapData mapData, int worldX, int worldZ) {
        if (mapData == null || mapData.getInfo() == null) {
            return "";
        }

        double scale = FMGHeightSampler.sampleScale();
        double offsetX = -(mapData.getInfo().getWidth() * scale) / 2.0;
        double offsetZ = -(mapData.getInfo().getHeight() * scale) / 2.0;

        double fmgX = (worldX - offsetX) / scale;
        double fmgY = (worldZ - offsetZ) / scale;

        FMGCell cell = mapData.findNearestCell(fmgX, fmgY);
        if (cell == null) {
            return "";
        }

        FMGBiome biomeDef = mapData.getBiome(cell.getBiome());
        if (biomeDef == null || biomeDef.getName() == null || biomeDef.getName().isBlank()) {
            return "";
        }

        return NON_WORD.matcher(biomeDef.getName().toLowerCase(Locale.ROOT)).replaceAll("");
    }
}
