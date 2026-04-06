package com.example.fmg;

import java.util.List;

import com.example.FantasyMapGenerator;
import com.example.worldmap.runtime.FMGMapDataCache;

import net.minecraft.util.math.MathHelper;

public class FMGHeightSampler {

    public static final double DEFAULT_SAMPLE_SCALE = 12.0;

    /**
     * @deprecated Use {@link #sampleScale()} instead (configurable).
     */
    @Deprecated
    public static final double SAMPLE_SCALE = DEFAULT_SAMPLE_SCALE;

    public static double sampleScale() {
        // Read from global config via FMGMapDataCache (cached).
        // Falls back to DEFAULT_SAMPLE_SCALE when config is missing/invalid.
        double s = FMGMapDataCache.sampleScale();
        if (!Double.isFinite(s) || s <= 0.0) {
            return DEFAULT_SAMPLE_SCALE;
        }
        return s;
    }

    // Defaults (used only when no MapInfo is available)
    private static final int DEFAULT_SEA_LEVEL = 63;
    private static final int DEFAULT_MIN_WORLD_Y = -64;
    /** Exclusive upper bound (top block is DEFAULT_MAX_WORLD_Y_EXCLUSIVE - 1). */
    private static final int DEFAULT_MAX_WORLD_Y_EXCLUSIVE = 400;

    // FMG semantics
    // <= 19 is "ocean side" (sea surface is at 19)
    private static final int FMG_SEA_LEVEL = 19;

    // Choose how deep the seabed should be when FMG height is 0.
    // This is NOT sea surface; it's the ocean floor for FMG=0.
    private static final int SEA_FLOOR_OFFSET = 30; // tweak: 30, 50, 80

    // Noise overlay settings for natural terrain variation
    private static final double NOISE_SCALE = 80.0; // Large scale for big waves
    private static final double NOISE_AMPLITUDE = 5.5; // Max height variation in blocks

    private static boolean loggedOnce = false;

    /**
     * Locate the nearest FMG cell for a world position, using the same
     * projection that height sampling relies on. Returns {@code null}
     * when the position is outside the FMG map bounds or map data is
     * missing.
     */
    public static FMGCell findCellAtWorldPos(FMGMapData map, int worldX, int worldZ) {
        if (map == null || map.getInfo() == null || map.getCells() == null) {
            return null;
        }

        double mapW = map.getInfo().getWidth();
        double mapH = map.getInfo().getHeight();

        double scale = sampleScale();
        double offsetX = -(mapW * scale) / 2.0;
        double offsetZ = -(mapH * scale) / 2.0;

        double fmgX = (worldX - offsetX) / scale;
        double fmgY = (worldZ - offsetZ) / scale;

        if (fmgX < 0 || fmgY < 0 || fmgX >= mapW || fmgY >= mapH) {
            return null;
        }

        return map.findNearestCell(fmgX, fmgY);
    }

    public static int sampleHeight(FMGMapData map, int worldX, int worldZ) {
        return sampleHeight(
                map,
                worldX,
                worldZ,
                DEFAULT_MIN_WORLD_Y,
                DEFAULT_MAX_WORLD_Y_EXCLUSIVE,
                DEFAULT_SEA_LEVEL
        );
    }

    /**
     * Sample a terrain height for the given world coordinates, clamped into
     * the provided vertical range.
     *
     * @param maxWorldYExclusive exclusive upper bound (top block is maxWorldYExclusive - 1)
     */
    public static int sampleHeight(
            FMGMapData map,
            int worldX,
            int worldZ,
            int minWorldY,
            int maxWorldYExclusive,
            int seaLevel
    ) {
        if (map == null || map.getInfo() == null) {
            return seaLevel;
        }

        if (maxWorldYExclusive <= minWorldY + 1) {
            return seaLevel;
        }

        int maxWorldYInclusive = maxWorldYExclusive - 1;

        double mapW = map.getInfo().getWidth();
        double mapH = map.getInfo().getHeight();

        double scale = sampleScale();
        double offsetX = -(mapW * scale) / 2.0;
        double offsetZ = -(mapH * scale) / 2.0;

        double fmgX = (worldX - offsetX) / scale;
        double fmgY = (worldZ - offsetZ) / scale;

        if (!loggedOnce && worldX == 0 && worldZ == 0) {
            FantasyMapGenerator.LOGGER.info(
                "FMG Height Sampling at (0,0): fmgX={}, fmgY={}, mapSize={}x{}",
                fmgX, fmgY, mapW, mapH
            );
            loggedOnce = true;
        }

        // Outside FMG map: treat as flat ocean at sea level.
        if (fmgX < 0 || fmgY < 0 || fmgX >= mapW || fmgY >= mapH) {
            return seaLevel;
        }

        // Lazily build the FMG-space heightmap once per FMG map and
        // cache it on the FMGMapData object. The heightfield is stored
        // in world Y units, indexed by FMG pixel coordinates.
        double[][] heightField = map.getCachedHeightField();
        if (heightField == null) {
            heightField = buildHeightField(map, minWorldY, seaLevel, maxWorldYInclusive);
            heightField = applyGaussianSmoothing(heightField);
            map.setCachedHeightField(heightField);
        }

        int width = heightField.length;
        if (width == 0) {
            return seaLevel;
        }
        int height = heightField[0].length;

        // Clamp sampling coordinates into the valid FMG raster range
        // and use bilinear interpolation in FMG space.
        double sx = MathHelper.clamp(fmgX, 0.0, width - 1.0001);
        double sz = MathHelper.clamp(fmgY, 0.0, height - 1.0001);

        int x0 = (int) Math.floor(sx);
        int z0 = (int) Math.floor(sz);
        int x1 = Math.min(x0 + 1, width - 1);
        int z1 = Math.min(z0 + 1, height - 1);

        double tx = sx - x0;
        double tz = sz - z0;

        double h00 = heightField[x0][z0];
        double h10 = heightField[x1][z0];
        double h01 = heightField[x0][z1];
        double h11 = heightField[x1][z1];

        double h0 = MathHelper.lerp(tx, h00, h10);
        double h1 = MathHelper.lerp(tx, h01, h11);
        double worldY = MathHelper.lerp(tz, h0, h1);

        // Apply multi-octave simplex noise overlay for natural terrain variation
        // Fade in based on height: flat near sea level, full effect at high elevations
        double noiseOffset = sampleTerrainNoise(worldX, worldZ, worldY);
        worldY += noiseOffset;

        return MathHelper.clamp((int) Math.round(worldY), minWorldY, maxWorldYInclusive);
    }

    /**
     * Sample multi-octave simplex noise for natural terrain variation.
     * Uses 3 octaves with decreasing amplitude to create wave-like patterns.
     * Noise fades in based on height - flat near sea level, full effect at high elevations.
     */
    private static double sampleTerrainNoise(int worldX, int worldZ, double currentHeight) {
        // Calculate fade factor based on height
        // No noise below y=65, gentle ramp up to y=90, full effect above y=140
        double fadeFactor;
        if (currentHeight <= 65.0) {
            fadeFactor = 0.0;
        } else if (currentHeight < 90.0) {
            // Gradual fade in from 65 to 90
            fadeFactor = (currentHeight - 65.0) / 25.0 * 0.15; // Up to 15% at y=90
        } else if (currentHeight < 140.0) {
            // Ramp up from 15% to 100% between y=90 and y=140
            double t = (currentHeight - 90.0) / 50.0;
            fadeFactor = 0.15 + t * 0.85; // From 15% to 100%
        } else {
            fadeFactor = 1.0; // Full effect above y=140
        }

        double noise = 0.0;

        double amplitude = NOISE_AMPLITUDE * fadeFactor;
        double frequency = 1.0 / NOISE_SCALE;

        // First octave: large waves
        noise += simplex2D(worldX * frequency, worldZ * frequency) * amplitude;

        // Second octave: medium detail
        amplitude *= 0.5;
        frequency *= 2.0;
        noise += simplex2D(worldX * frequency + 100, worldZ * frequency + 100) * amplitude;

        // Third octave: fine detail
        amplitude *= 0.5;
        frequency *= 2.0;
        noise += simplex2D(worldX * frequency + 200, worldZ * frequency + 200) * amplitude;

        return noise;
    }

    /**
     * 2D Simplex noise implementation.
     * Returns values roughly in range [-1, 1].
     */
    private static double simplex2D(double x, double y) {
        final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
        final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;

        double n0, n1, n2;

        // Skew the input space to determine which simplex cell we're in
        double s = (x + y) * F2;
        int i = fastFloor(x + s);
        int j = fastFloor(y + s);

        double t = (i + j) * G2;
        double X0 = i - t;
        double Y0 = j - t;
        double x0 = x - X0;
        double y0 = y - Y0;

        // Determine which simplex we are in
        int i1, j1;
        if (x0 > y0) {
            i1 = 1;
            j1 = 0;
        } else {
            i1 = 0;
            j1 = 1;
        }

        // Offsets for corners
        double x1 = x0 - i1 + G2;
        double y1 = y0 - j1 + G2;
        double x2 = x0 - 1.0 + 2.0 * G2;
        double y2 = y0 - 1.0 + 2.0 * G2;

        // Work out the hashed gradient indices
        int gi0 = hash(i, j);
        int gi1 = hash(i + i1, j + j1);
        int gi2 = hash(i + 1, j + 1);

        // Calculate the contribution from the three corners
        double t0 = 0.5 - x0 * x0 - y0 * y0;
        if (t0 < 0) {
            n0 = 0.0;
        } else {
            t0 *= t0;
            n0 = t0 * t0 * grad2D(gi0, x0, y0);
        }

        double t1 = 0.5 - x1 * x1 - y1 * y1;
        if (t1 < 0) {
            n1 = 0.0;
        } else {
            t1 *= t1;
            n1 = t1 * t1 * grad2D(gi1, x1, y1);
        }

        double t2 = 0.5 - x2 * x2 - y2 * y2;
        if (t2 < 0) {
            n2 = 0.0;
        } else {
            t2 *= t2;
            n2 = t2 * t2 * grad2D(gi2, x2, y2);
        }

        // Add contributions from each corner and scale to [-1, 1]
        return 70.0 * (n0 + n1 + n2);
    }

    private static int fastFloor(double x) {
        int xi = (int) x;
        return x < xi ? xi - 1 : xi;
    }

    private static int hash(int i, int j) {
        // Simple hash function for gradient selection
        int n = i * 374761393 + j * 668265263;
        n = (n ^ (n >>> 13)) * 1274126177;
        return (n ^ (n >>> 16)) & 255;
    }

    private static double grad2D(int hash, double x, double y) {
        // Convert hash to gradient direction
        int h = hash & 7;
        double u = h < 4 ? x : y;
        double v = h < 4 ? y : x;
        return ((h & 1) != 0 ? -u : u) + ((h & 2) != 0 ? -2.0 * v : 2.0 * v);
    }

    /**
     * Build a 2D FMG-space heightfield from the cell data.
     *
     * Semantics (per your design):
    * - Ocean is completely ignored for shaping and rendered flat at sea level.
    * - Land cells (h > FMG_SEA_LEVEL) use their FMG height in [19..100]
    *   mapped linearly to Minecraft Y in [seaLevel..maxWorldYInclusive].
     * - Heights are smoothly interpolated across the island using inverse
     *   distance weighting of nearby cells in FMG space.
     */
    private static double[][] buildHeightField(FMGMapData map, int minWorldY, int seaLevel, int maxWorldYInclusive) {
        FMGMapInfo info = map.getInfo();
        int width = info != null ? info.getWidth() : 0;
        int height = info != null ? info.getHeight() : 0;

        if (width <= 0 || height <= 0 || map.getCells() == null || map.getCells().isEmpty()) {
            return new double[0][0];
        }

        double[][] field = new double[width][height];

        // Precompute land/water classification per cell id
        int maxCellId = -1;
        for (FMGCell cell : map.getCells()) {
            if (cell.getI() > maxCellId) {
                maxCellId = cell.getI();
            }
        }
        boolean[] isLandCell = new boolean[maxCellId + 1];
        for (FMGCell cell : map.getCells()) {
            int id = cell.getI();
            if (id >= 0 && id < isLandCell.length) {
                isLandCell[id] = cell.getH() > FMG_SEA_LEVEL;
            }
        }

        // Precompute which vertices lie on a land/water border
        List<FMGVertex> vertices = map.getVertices();
        boolean[] isCoastVertex = null;
        if (vertices != null && !vertices.isEmpty()) {
            int maxVertexId = -1;
            for (FMGVertex v : vertices) {
                if (v.getI() > maxVertexId) {
                    maxVertexId = v.getI();
                }
            }
            isCoastVertex = new boolean[maxVertexId + 1];

            for (FMGVertex v : vertices) {
                int[] adjCells = v.getC();
                if (adjCells == null || adjCells.length == 0) continue;

                boolean hasLand = false;
                boolean hasWater = false;
                for (int cid : adjCells) {
                    if (cid < 0 || cid > maxCellId) continue;
                    if (isLandCell[cid]) {
                        hasLand = true;
                    } else {
                        hasWater = true;
                    }
                }

                if (hasLand && hasWater) {
                    int id = v.getI();
                    if (id >= 0 && id < isCoastVertex.length) {
                        isCoastVertex[id] = true;
                    }
                }
            }
        }

        // For each FMG pixel coordinate, classify ocean vs land by the
        // nearest cell, then either:
        // - assign flat sea level (ocean), or
        // - blend nearby land cell centers and coastal vertices in world
        //   Y units via inverse-distance weighting.
        for (int y = 0; y < height; y++) {
            double fmgY = y + 0.5; // sample near pixel center
            for (int x = 0; x < width; x++) {
                double fmgX = x + 0.5;

                FMGCell nearest = map.findNearestCell(fmgX, fmgY);
                if (nearest == null) {
                    field[x][y] = seaLevel;
                    continue;
                }

                int nearestH = nearest.getH();

                // Water: compute a seabed profile that starts at 63 near
                // the coast and gradually drops towards the deep ocean.
                if (nearestH <= FMG_SEA_LEVEL) {
                    double distToShore = computeDistanceToShore(map, isLandCell, isCoastVertex, nearest, fmgX, fmgY);
                    double seabedY = computeWaterSeabedY(distToShore, minWorldY, seaLevel);
                    field[x][y] = seabedY;
                    continue;
                }

                double weighted = 0.0;
                double totalW = 0.0;

                // 1) Land cell centers as control points, mapped into
                //    world Y and blended with inverse-distance weights.
                List<FMGCell> neighbors = map.findNearestCells(fmgX, fmgY, 6);
                for (FMGCell n : neighbors) {
                    int id = n.getI();
                    if (id < 0 || id >= isLandCell.length || !isLandCell[id]) {
                        continue; // ignore pure water cells for land shaping
                    }

                    double dx = n.getPx() - fmgX;
                    double dy = n.getPy() - fmgY;
                    double dist = Math.max(0.001, Math.sqrt(dx * dx + dy * dy));
                    double w = 1.0 / dist;

                        // Map this cell's FMG height into world Y in
                        // [seaLevel .. maxWorldYInclusive].
                    double fmgH = Math.max(FMG_SEA_LEVEL, n.getH());
                    double t = MathHelper.clamp(
                            (fmgH - FMG_SEA_LEVEL) / (100.0 - FMG_SEA_LEVEL),
                            0.0,
                            1.0
                    );
                        double landY = MathHelper.lerp(t, seaLevel, maxWorldYInclusive);

                    weighted += landY * w;
                    totalW += w;
                }

                // 2) Coastal vertices that belong to this land cell and
                //    also touch water: treat them as fixed at sea level.
                if (isCoastVertex != null) {
                    int[] vIdx = nearest.getV();
                    if (vIdx != null) {
                        for (int vid : vIdx) {
                            if (vid < 0 || vid >= isCoastVertex.length) continue;
                            if (!isCoastVertex[vid]) continue;

                            FMGVertex v = map.getVertex(vid);
                            if (v == null) continue;

                            double vx = v.getPx();
                            double vy = v.getPy();
                            double dx = vx - fmgX;
                            double dy = vy - fmgY;
                            double dist = Math.max(0.001, Math.sqrt(dx * dx + dy * dy));
                            double w = 1.0 / dist;

                            weighted += seaLevel * w;
                            totalW += w;
                        }
                    }
                }

                if (totalW <= 0.0) {
                    field[x][y] = seaLevel;
                    continue;
                }

                double worldY = weighted / totalW;
                field[x][y] = worldY;
            }
        }

        return field;
    }

    /**
     * Approximate horizontal distance from a water cell sample point to the
     * nearest shoreline, using local land cells and coastal vertices.
     */
    private static double computeDistanceToShore(
            FMGMapData map,
            boolean[] isLandCell,
            boolean[] isCoastVertex,
            FMGCell waterCell,
            double fmgX,
            double fmgY
    ) {
        double minDist = Double.POSITIVE_INFINITY;

        // 1) Land cell centers among this cell and its neighbors.
        int waterId = waterCell.getI();
        if (waterId >= 0 && waterId < isLandCell.length && isLandCell[waterId]) {
            double dx = waterCell.getPx() - fmgX;
            double dy = waterCell.getPy() - fmgY;
            minDist = Math.min(minDist, Math.sqrt(dx * dx + dy * dy));
        }

        int[] neighborIds = waterCell.getC();
        if (neighborIds != null) {
            for (int cid : neighborIds) {
                if (cid < 0 || cid >= isLandCell.length || !isLandCell[cid]) continue;
                FMGCell land = map.getCell(cid);
                if (land == null) continue;
                double dx = land.getPx() - fmgX;
                double dy = land.getPy() - fmgY;
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < minDist) {
                    minDist = dist;
                }
            }
        }

        // 2) Coastal vertices attached to this water cell and its neighbors.
        if (isCoastVertex != null) {
            // Scan vertices of the water cell itself.
            int[] vids = waterCell.getV();
            if (vids != null) {
                for (int vid : vids) {
                    if (vid < 0 || vid >= isCoastVertex.length) continue;
                    if (!isCoastVertex[vid]) continue;
                    FMGVertex v = map.getVertex(vid);
                    if (v == null) continue;
                    double vx = v.getPx();
                    double vy = v.getPy();
                    double dx = vx - fmgX;
                    double dy = vy - fmgY;
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist < minDist) {
                        minDist = dist;
                    }
                }
            }

            // Scan vertices of neighboring cells as well (to catch
            // shorelines slightly beyond the immediate cell).
            if (neighborIds != null) {
                for (int cid : neighborIds) {
                    FMGCell c = map.getCell(cid);
                    if (c == null) continue;
                    int[] nVids = c.getV();
                    if (nVids == null) continue;
                    for (int vid : nVids) {
                        if (vid < 0 || vid >= isCoastVertex.length) continue;
                        if (!isCoastVertex[vid]) continue;
                        FMGVertex v = map.getVertex(vid);
                        if (v == null) continue;
                        double vx = v.getPx();
                        double vy = v.getPy();
                        double dx = vx - fmgX;
                        double dy = vy - fmgY;
                        double dist = Math.sqrt(dx * dx + dy * dy);
                        if (dist < minDist) {
                            minDist = dist;
                        }
                    }
                }
            }
        }

        if (!Double.isFinite(minDist)) {
            // Fallback: treat as very far from shore.
            return 9999.0;
        }

        // IMPORTANT: Keep ocean depth falloff stable regardless of the
        // configurable world<->FMG projection scale.
        //
        // Distances above are measured in FMG units (cell/vertex coordinates).
        // For a given world-space distance, FMG-space distance scales as:
        //   d_fmg = d_world / sampleScale
        // We want the seabed profile to behave like DEFAULT_SAMPLE_SCALE even
        // when sampleScale is changed, so convert to an equivalent distance at
        // DEFAULT_SAMPLE_SCALE:
        //   d_norm = d_world / DEFAULT = d_fmg * sampleScale / DEFAULT
        double scale = sampleScale();
        double normFactor = scale / DEFAULT_SAMPLE_SCALE;
        if (!Double.isFinite(normFactor) || normFactor <= 0.0) {
            normFactor = 1.0;
        }

        return minDist * normFactor;
    }

    /**
     * Apply a light Gaussian smoothing filter to reduce sharp edges in the
     * height field. Uses a 5x5 kernel with moderate sigma for a subtle effect
     * that preserves terrain features while removing artifacts.
     */
    private static double[][] applyGaussianSmoothing(double[][] field) {
        if (field == null || field.length == 0 || field[0].length == 0) {
            return field;
        }

        int width = field.length;
        int height = field[0].length;

        // 5x5 Gaussian kernel with sigma ~1.0 (normalized)
        double[][] kernel = {
            {0.003765, 0.015019, 0.023792, 0.015019, 0.003765},
            {0.015019, 0.059912, 0.094907, 0.059912, 0.015019},
            {0.023792, 0.094907, 0.150342, 0.094907, 0.023792},
            {0.015019, 0.059912, 0.094907, 0.059912, 0.015019},
            {0.003765, 0.015019, 0.023792, 0.015019, 0.003765}
        };

        double[][] smoothed = new double[width][height];

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                double sum = 0.0;
                double weightSum = 0.0;

                // Apply kernel
                for (int ky = 0; ky < 5; ky++) {
                    for (int kx = 0; kx < 5; kx++) {
                        int sampleX = x + kx - 2;
                        int sampleY = y + ky - 2;

                        // Clamp to edges rather than wrapping
                        sampleX = MathHelper.clamp(sampleX, 0, width - 1);
                        sampleY = MathHelper.clamp(sampleY, 0, height - 1);

                        double weight = kernel[ky][kx];
                        sum += field[sampleX][sampleY] * weight;
                        weightSum += weight;
                    }
                }

                smoothed[x][y] = sum / weightSum;
            }
        }

        return smoothed;
    }

    /**
     * Map a horizontal distance from shore to a seabed Y level.
     * Starts at SEA_LEVEL near the coast, then gradually drops to
     * a deep plateau around Y=5.
     */
    private static double computeWaterSeabedY(double distance, int minWorldY, int seaLevel) {
        if (distance <= 0.0) {
            return seaLevel;
        }

        // Shelf and deep-ocean distances in FMG units.
        double shelfRadius = 20.0;  // gentle drop near shore
        double deepRadius = 30.0;  // beyond this, clamp to deep plateau
        double seaFloorY = Math.max((double) (seaLevel - SEA_FLOOR_OFFSET), (double) (minWorldY));
        double shallowDrop = 15.0;  // seaLevel -> (seaLevel-15) across shelf
        double deepFloor = Math.min(5.0, seaFloorY);

        if (distance <= shelfRadius) {
            double t = distance / shelfRadius; // 0..1
            return seaLevel - t * shallowDrop;
        }

        if (distance >= deepRadius) {
            return deepFloor;
        }

        double midStartY = seaLevel - shallowDrop;
        double t = (distance - shelfRadius) / (deepRadius - shelfRadius); // 0..1
        return MathHelper.lerp(t, midStartY, deepFloor);
    }
}
