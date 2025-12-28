package com.example.fmg;

import java.util.List;

import com.example.FantasyMapGenerator;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.noise.SimplexNoiseSampler;
import net.minecraft.util.math.random.Random;

public class FMGHeightSampler {
    
    private static final SimplexNoiseSampler CONTINENTAL_NOISE = new SimplexNoiseSampler(Random.create(0xF00DABCDL));
    private static final SimplexNoiseSampler DETAIL_NOISE = new SimplexNoiseSampler(Random.create(0xDEADBEEFL));
    public static final double SAMPLE_SCALE = 6.0;
    private static final int SEA_LEVEL = 63;
    private static final int TARGET_MAX_HEIGHT = 140;
    private static final int MIN_WORLD_Y = -64;
    private static final int MAX_WORLD_Y = 320;
    private static boolean loggedOnce = false;

    public static int sampleHeight(
        FMGMapData map,
        int worldX,
        int worldZ
    ) {
        if (map == null || map.getInfo() == null) {
            return SEA_LEVEL;
        }

        double offsetX = -(map.getInfo().getWidth()  * SAMPLE_SCALE) / 2.0;
        double offsetZ = -(map.getInfo().getHeight() * SAMPLE_SCALE) / 2.0;

        double fmgX = (worldX - offsetX) / SAMPLE_SCALE;
        double fmgY = (worldZ - offsetZ) / SAMPLE_SCALE;
        
        // Log once for debugging
        if (!loggedOnce && worldX == 0 && worldZ == 0) {
            FantasyMapGenerator.LOGGER.info("FMG Height Sampling at (0,0): fmgX={}, fmgY={}, mapSize={}x{}", 
                fmgX, fmgY, map.getInfo().getWidth(), map.getInfo().getHeight());
            loggedOnce = true;
        }

        if (fmgX < 0 || fmgY < 0 ||
            fmgX >= map.getInfo().getWidth() ||
            fmgY >= map.getInfo().getHeight()) {
            return SEA_LEVEL - 5;
        }

        List<FMGCell> neighbors = map.findNearestCells(fmgX, fmgY, 6);
        if (neighbors.isEmpty()) {
            return SEA_LEVEL - 5;
        }

        double weightedHeight = 0;
        double totalWeight = 0;
        double mean = 0;

        for (FMGCell neighbor : neighbors) {
            double dx = neighbor.getPx() - fmgX;
            double dy = neighbor.getPy() - fmgY;
            double distance = Math.max(0.001, Math.sqrt(dx * dx + dy * dy));
            double weight = 1.0 / distance;
            double normalizedHeight = neighbor.getH() / 100.0;

            weightedHeight += normalizedHeight * weight;
            totalWeight += weight;
            mean += normalizedHeight;
        }

        double baseHeight = weightedHeight / totalWeight;
        mean /= neighbors.size();

        double variance = 0;
        for (FMGCell neighbor : neighbors) {
            double normalizedHeight = neighbor.getH() / 100.0;
            variance += Math.pow(normalizedHeight - mean, 2);
        }
        variance /= neighbors.size();

        double slopeSuppression = MathHelper.clamp(1.0 - variance * 4.0, 0.4, 1.0);
        baseHeight = MathHelper.lerp(0.35, baseHeight, slopeSuppression);

        double worldHeight = SEA_LEVEL + baseHeight * (TARGET_MAX_HEIGHT - SEA_LEVEL);

        double continental = CONTINENTAL_NOISE.sample(worldX * 0.0025, worldZ * 0.0025) * 40.0;
        double detail = DETAIL_NOISE.sample(worldX * 0.025, worldZ * 0.025) * 2.5;
        worldHeight += continental + detail;

        if (worldHeight < SEA_LEVEL + 6) {
            double coastFactor = MathHelper.clamp((worldHeight - (SEA_LEVEL - 18)) / 24.0, 0.0, 1.0);
            double vanillaOceanHeight = SEA_LEVEL - 42 + continental * 1.1;
            worldHeight = MathHelper.lerp(coastFactor, vanillaOceanHeight, worldHeight);
        }

        if (worldHeight > SEA_LEVEL) {
            double normalized = (worldHeight - SEA_LEVEL) / (TARGET_MAX_HEIGHT - SEA_LEVEL);
            double curved = Math.pow(normalized, 1.18);
            worldHeight = SEA_LEVEL + curved * (TARGET_MAX_HEIGHT - SEA_LEVEL);
        } else {
            double normalized = (SEA_LEVEL - worldHeight) / (SEA_LEVEL - (MIN_WORLD_Y + 5));
            double curved = Math.pow(normalized, 0.82);
            worldHeight = SEA_LEVEL - curved * (SEA_LEVEL - (MIN_WORLD_Y + 5));
        }

        int finalHeight = MathHelper.clamp((int)Math.round(worldHeight), MIN_WORLD_Y, MAX_WORLD_Y - 1);
        return finalHeight;
    }


}
