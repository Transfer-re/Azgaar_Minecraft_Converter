package com.example.fmg;

import java.util.List;

import com.example.FantasyMapGenerator;
import net.minecraft.util.math.MathHelper;

public class FMGHeightSampler {

    public static final double SAMPLE_SCALE = 6.0;

    // Minecraft 1.21.4
    private static final int SEA_LEVEL = 63;
    private static final int MIN_WORLD_Y = -64;
    private static final int MAX_WORLD_Y = 320; // build height cap (top is MAX_WORLD_Y-1)

    // FMG semantics
    // <= 19 is "ocean side" (sea surface is at 19)
    private static final int FMG_SEA_LEVEL = 19;

    // Choose how deep the seabed should be when FMG height is 0.
    // This is NOT sea surface; it's the ocean floor for FMG=0.
    private static final int SEA_FLOOR_Y = SEA_LEVEL - 50; // tweak: -30, -50, -80, or MIN_WORLD_Y

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

        double offsetX = -(mapW * SAMPLE_SCALE) / 2.0;
        double offsetZ = -(mapH * SAMPLE_SCALE) / 2.0;

        double fmgX = (worldX - offsetX) / SAMPLE_SCALE;
        double fmgY = (worldZ - offsetZ) / SAMPLE_SCALE;

        if (fmgX < 0 || fmgY < 0 || fmgX >= mapW || fmgY >= mapH) {
            return null;
        }

        return map.findNearestCell(fmgX, fmgY);
    }

    public static int sampleHeight(FMGMapData map, int worldX, int worldZ) {

        if (map == null || map.getInfo() == null) {
            return SEA_LEVEL;
        }

        double mapW = map.getInfo().getWidth();
        double mapH = map.getInfo().getHeight();

        double offsetX = -(mapW * SAMPLE_SCALE) / 2.0;
        double offsetZ = -(mapH * SAMPLE_SCALE) / 2.0;

        double fmgX = (worldX - offsetX) / SAMPLE_SCALE;
        double fmgY = (worldZ - offsetZ) / SAMPLE_SCALE;

        if (!loggedOnce && worldX == 0 && worldZ == 0) {
            FantasyMapGenerator.LOGGER.info(
                "FMG Height Sampling at (0,0): fmgX={}, fmgY={}, mapSize={}x{}",
                fmgX, fmgY, mapW, mapH
            );
            loggedOnce = true;
        }

        // Outside FMG map: treat as flat ocean at sea level.
        if (fmgX < 0 || fmgY < 0 || fmgX >= mapW || fmgY >= mapH) {
            return SEA_LEVEL;
        }

        // Lazily build the FMG-space heightmap once per FMG map and
        // cache it on the FMGMapData object. The heightfield is stored
        // in world Y units, indexed by FMG pixel coordinates.
        double[][] heightField = map.getCachedHeightField();
        if (heightField == null) {
            heightField = buildHeightField(map);
            map.setCachedHeightField(heightField);
        }

        int width = heightField.length;
        if (width == 0) {
            return SEA_LEVEL;
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

        return MathHelper.clamp((int) Math.round(worldY), MIN_WORLD_Y, MAX_WORLD_Y - 1);
    }

    /**
     * Build a 2D FMG-space heightfield from the cell data.
     *
     * Semantics (per your design):
     * - Ocean is completely ignored for shaping and rendered flat at Y=63.
     * - Land cells (h > FMG_SEA_LEVEL) use their FMG height in [19..100]
     *   mapped linearly to Minecraft Y in [SEA_LEVEL..(MAX_WORLD_Y-1)].
     * - Heights are smoothly interpolated across the island using inverse
     *   distance weighting of nearby cells in FMG space.
     */
    private static double[][] buildHeightField(FMGMapData map) {
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
                    field[x][y] = SEA_LEVEL;
                    continue;
                }

                int nearestH = nearest.getH();

                // Water: compute a seabed profile that starts at 63 near
                // the coast and gradually drops towards the deep ocean.
                if (nearestH <= FMG_SEA_LEVEL) {
                    double distToShore = computeDistanceToShore(map, isLandCell, isCoastVertex, nearest, fmgX, fmgY);
                    double seabedY = computeWaterSeabedY(distToShore);
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
                    // [SEA_LEVEL .. MAX_WORLD_Y-1].
                    double fmgH = Math.max(FMG_SEA_LEVEL, n.getH());
                    double t = MathHelper.clamp(
                            (fmgH - FMG_SEA_LEVEL) / (100.0 - FMG_SEA_LEVEL),
                            0.0,
                            1.0
                    );
                    double landY = MathHelper.lerp(t, SEA_LEVEL, MAX_WORLD_Y - 1);

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

                            weighted += SEA_LEVEL * w;
                            totalW += w;
                        }
                    }
                }

                if (totalW <= 0.0) {
                    field[x][y] = SEA_LEVEL;
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
        return minDist;
    }

    /**
     * Map a horizontal distance from shore to a seabed Y level.
     * Starts at SEA_LEVEL near the coast, then gradually drops to
     * a deep plateau around Y=5.
     */
    private static double computeWaterSeabedY(double distance) {
        if (distance <= 0.0) {
            return SEA_LEVEL;
        }

        // Shelf and deep-ocean distances in FMG units.
        double shelfRadius = 20.0;  // gentle drop near shore
        double deepRadius = 120.0;  // beyond this, clamp to deep plateau
        double shallowDrop = 15.0;  // 63 -> 48 across continental shelf
        double deepFloor = 5.0;     // asymptotic deep ocean floor

        if (distance <= shelfRadius) {
            double t = distance / shelfRadius; // 0..1
            return SEA_LEVEL - t * shallowDrop;
        }

        if (distance >= deepRadius) {
            return deepFloor;
        }

        double midStartY = SEA_LEVEL - shallowDrop; // 48
        double t = (distance - shelfRadius) / (deepRadius - shelfRadius); // 0..1
        return MathHelper.lerp(t, midStartY, deepFloor);
    }
}
