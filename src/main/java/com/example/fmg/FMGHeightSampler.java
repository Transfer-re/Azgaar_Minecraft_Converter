package com.example.fmg;

import java.util.List;

import com.example.FantasyMapGenerator;
import net.minecraft.util.math.MathHelper;

public class FMGHeightSampler {

    public static final double SAMPLE_SCALE = 6.0;

    // Minecraft limits
    private static final int MIN_WORLD_Y = -64;
    private static final int MAX_WORLD_Y = 319;

    // FMG semantics
    private static final int FMG_OCEAN_THRESHOLD = 19;
    private static final int FMG_MAX_HEIGHT = 100;

    // Coastal slope width (FMG units)
    private static final double COAST_WIDTH = 6.0;

    private static boolean loggedOnce = false;

    public static int sampleHeight(FMGMapData map, int worldX, int worldZ) {

        if (map == null || map.getInfo() == null) {
            return 0;
        }

        double mapW = map.getInfo().getWidth();
        double mapH = map.getInfo().getHeight();

        double offsetX = -(mapW * SAMPLE_SCALE) / 2.0;
        double offsetZ = -(mapH * SAMPLE_SCALE) / 2.0;

        double fmgX = (worldX - offsetX) / SAMPLE_SCALE;
        double fmgY = (worldZ - offsetZ) / SAMPLE_SCALE;

        if (!loggedOnce && worldX == 0 && worldZ == 0) {
            FantasyMapGenerator.LOGGER.info(
                "FMG island heightmap (constraint-based) @ (0,0): fmgX={}, fmgY={}",
                fmgX, fmgY
            );
            loggedOnce = true;
        }

        if (fmgX < 0 || fmgY < 0 || fmgX >= mapW || fmgY >= mapH) {
            return 0;
        }

        List<FMGCell> neighbors = map.findNearestCells(fmgX, fmgY, 8);
        if (neighbors.isEmpty()) {
            return 0;
        }

        double landHeightSum = 0.0;
        double landWeightSum = 0.0;
        double minOceanDist = Double.MAX_VALUE;
        boolean nearestIsOcean = false;

        for (FMGCell n : neighbors) {
            double dx = n.getPx() - fmgX;
            double dy = n.getPy() - fmgY;
            double dist = Math.max(0.001, Math.sqrt(dx * dx + dy * dy));

            if (n.getH() <= FMG_OCEAN_THRESHOLD) {
                minOceanDist = Math.min(minOceanDist, dist);
                if (dist < 1.0) nearestIsOcean = true;
                continue;
            }

            double w = 1.0 / dist;
            landHeightSum += (n.getH() / (double) FMG_MAX_HEIGHT) * w;
            landWeightSum += w;
        }

        // Ocean
        if (nearestIsOcean || landWeightSum == 0.0) {
            return 0;
        }

        // Distance-based coast slope
        if (minOceanDist == Double.MAX_VALUE) {
            minOceanDist = COAST_WIDTH;
        }

        double coastFactor = MathHelper.clamp(
            minOceanDist / COAST_WIDTH,
            0.0,
            1.0
        );

        // Smoothstep
        coastFactor = coastFactor * coastFactor * (3.0 - 2.0 * coastFactor);

        double normalizedHeight =
            (landHeightSum / landWeightSum) * coastFactor;

        double worldHeight = normalizedHeight * 320.0;

        return MathHelper.clamp(
            (int)Math.round(worldHeight),
            MIN_WORLD_Y,
            MAX_WORLD_Y
        );
    }
}
