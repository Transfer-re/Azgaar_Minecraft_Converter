package com.example.fmg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete FMG map data loaded into memory.
 * This is the main container for all map information.
 */
public class FMGMapData {
    private FMGMapInfo info;
    private List<FMGCell> cells;
    private List<FMGBurg> burgs;
    private List<FMGBiome> biomes;
    private List<FMGState> states;
    private List<FMGVertex> vertices;
    private List<FMGRoute> routes;
    private List<FMGRiver> rivers;
    private List<FMGProvince> provinces;
    // Lookup maps for fast access
    private Map<Integer, FMGCell> cellMap;
    private Map<Integer, FMGBurg> burgMap;
    private Map<Integer, FMGBiome> biomeMap;
    private Map<Integer, FMGState> stateMap;
    private Map<Integer, FMGVertex> vertexMap;
    private Map<Integer, FMGRoute> routeMap;
    private Map<Integer, FMGRiver> riverMap;
    private Map<Integer, FMGProvince> provinceMap;
    private Map<Integer, Integer> cellToProvince = new HashMap<>();

    // Accelerate nearest-cell queries (used by Debug HUD + height sampling).
    // This is transient runtime state derived from cells.
    private transient KdNode cellKdRoot;
    // Lazily built FMG-space heightfield (in world Y units), indexed
    // as [x][y] for 0 <= x < info.width, 0 <= y < info.height.
    // This is not serialized; it's derived from the FMG cells at runtime.
    private transient double[][] cachedHeightField;
    
    public FMGMapData() {
        this.cellMap = new HashMap<>();
        this.burgMap = new HashMap<>();
        this.biomeMap = new HashMap<>();
        this.stateMap = new HashMap<>();
        this.vertexMap = new HashMap<>();
        this.routeMap = new HashMap<>();
        this.riverMap = new HashMap<>();
        this.provinceMap = new HashMap<>();
    }
    
    // Getters and setters
    public FMGMapInfo getInfo() { return info; }
    public void setInfo(FMGMapInfo info) { this.info = info; }
    
    public List<FMGCell> getCells() { return cells; }
    public void setCells(List<FMGCell> cells) {
        this.cells = cells;
        // Build lookup map
        cellMap.clear();
        if (cells != null) {
            for (FMGCell cell : cells) {
                cellMap.put(cell.getI(), cell);
            }
        }

        rebuildCellKdTree();

        rebuildCellProvinceIndex();
    }
    
    public List<FMGBurg> getBurgs() { return burgs; }
    public void setBurgs(List<FMGBurg> burgs) {
        this.burgs = burgs;
        // Build lookup map
        burgMap.clear();
        if (burgs != null) {
            for (FMGBurg burg : burgs) {
                burgMap.put(burg.getI(), burg);
            }
        }
    }
    
    public List<FMGBiome> getBiomes() { return biomes; }
    public void setBiomes(List<FMGBiome> biomes) {
        this.biomes = biomes;
        // Build lookup map
        biomeMap.clear();
        if (biomes != null) {
            for (FMGBiome biome : biomes) {
                biomeMap.put(biome.getI(), biome);
            }
        }
    }
    
    public List<FMGState> getStates() { return states; }
    public void setStates(List<FMGState> states) {
        this.states = states;
        // Build lookup map
        stateMap.clear();
        if (states != null) {
            for (FMGState state : states) {
                stateMap.put(state.getI(), state);
            }
        }
    }

    public List<FMGVertex> getVertices() { return vertices; }
    public void setVertices(List<FMGVertex> vertices) {
        this.vertices = vertices;
        vertexMap.clear();
        if (vertices != null) {
            for (FMGVertex v : vertices) {
                vertexMap.put(v.getI(), v);
            }
        }
    }

    public List<FMGRoute> getRoutes() { return routes; }
    public void setRoutes(List<FMGRoute> routes) {
        this.routes = routes;
        routeMap.clear();
        if (routes != null) {
            for (FMGRoute route : routes) {
                routeMap.put(route.getI(), route);
            }
        }
    }

    public List<FMGRiver> getRivers() { return rivers; }
    public void setRivers(List<FMGRiver> rivers) {
        this.rivers = rivers;
        riverMap.clear();
        if (rivers != null) {
            for (FMGRiver river : rivers) {
                riverMap.put(river.getI(), river);
            }
        }
    }


    public List<FMGProvince> getProvinces() { return provinces; }
    public void setProvinces(List<FMGProvince> provinces) {
        this.provinces = provinces;
        provinceMap.clear();

        if (provinces != null) {
            for (FMGProvince province : provinces) {
                provinceMap.put(province.getI(), province);
            }
        }

        rebuildCellProvinceIndex();
    }

    /**
     * Returns the cached FMG-space heightfield, if any. May be {@code null}
     * if it has not been built yet.
     */
    public synchronized double[][] getCachedHeightField() {
        return cachedHeightField;
    }

    /**
     * Stores a precomputed FMG-space heightfield. Intended for use by
     * FMGHeightSampler or other runtime systems that want a 2D heightmap
     * instead of sampling cell neighbourhoods on every query.
     */
    public synchronized void setCachedHeightField(double[][] cachedHeightField) {
        this.cachedHeightField = cachedHeightField;
    }
    
    // Lookup methods for fast access
    public FMGCell getCell(int id) { return cellMap.get(id); }
    public FMGBurg getBurg(int id) { return burgMap.get(id); }
    public FMGBiome getBiome(int id) { return biomeMap.get(id); }
    public FMGState getState(int id) { return stateMap.get(id); }
    public FMGVertex getVertex(int id) { return vertexMap.get(id); }
    public FMGRoute getRoute(int id) { return routeMap.get(id); }
    public FMGRiver getRiver(int id) { return riverMap.get(id); }
    public FMGProvince getProvince(int id) { return provinceMap.get(id); }

    /** Province assigned to the given cell, or {@code null} if none. */
    public Integer getProvinceIdForCell(int cellId) { return cellToProvince.get(cellId); }

    /** Province assigned to the given cell, or {@code null} if none. */
    public FMGProvince getProvinceForCell(int cellId) {
        Integer id = cellToProvince.get(cellId);
        return id != null ? provinceMap.get(id) : null;
    }
    // Helper to find the nearest cell to a coordinate
    public FMGCell findNearestCell(double x, double y) {
        if (cells == null || cells.isEmpty()) {
            return null;
        }

        KdNode root = cellKdRoot;
        if (root == null) {
            // Fallback to brute force (should be rare; tree is rebuilt on setCells).
            FMGCell nearest = null;
            double minDist = Double.MAX_VALUE;
            for (FMGCell cell : cells) {
                double dx = cell.getPx() - x;
                double dy = cell.getPy() - y;
                double dist = dx * dx + dy * dy;
                if (dist < minDist) {
                    minDist = dist;
                    nearest = cell;
                }
            }
            return nearest;
        }

        Best best = new Best();
        best.distSq = Double.MAX_VALUE;
        nearestKd(root, x, y, best);
        return best.cell;
    }

    private void rebuildCellKdTree() {
        if (cells == null || cells.isEmpty()) {
            cellKdRoot = null;
            return;
        }

        // Copy to array list for sorting. Cells may be large; keep references only.
        List<FMGCell> pts = new ArrayList<>(cells.size());
        for (FMGCell c : cells) {
            if (c != null) {
                pts.add(c);
            }
        }
        cellKdRoot = buildKd(pts, 0);
    }

    private static KdNode buildKd(List<FMGCell> pts, int depth) {
        if (pts == null || pts.isEmpty()) {
            return null;
        }

        int axis = depth & 1;
        pts.sort(axis == 0
                ? Comparator.comparingDouble(FMGCell::getPx)
                : Comparator.comparingDouble(FMGCell::getPy));

        int mid = pts.size() / 2;
        FMGCell pivot = pts.get(mid);
        KdNode node = new KdNode(pivot, axis);

        if (mid > 0) {
            node.left = buildKd(new ArrayList<>(pts.subList(0, mid)), depth + 1);
        }
        if (mid + 1 < pts.size()) {
            node.right = buildKd(new ArrayList<>(pts.subList(mid + 1, pts.size())), depth + 1);
        }
        return node;
    }

    private static void nearestKd(KdNode node, double x, double y, Best best) {
        if (node == null) {
            return;
        }

        FMGCell c = node.cell;
        double dx = c.getPx() - x;
        double dy = c.getPy() - y;
        double distSq = dx * dx + dy * dy;
        if (distSq < best.distSq) {
            best.distSq = distSq;
            best.cell = c;
        }

        double diff = node.axis == 0 ? (x - c.getPx()) : (y - c.getPy());
        KdNode near = diff < 0 ? node.left : node.right;
        KdNode far = diff < 0 ? node.right : node.left;

        nearestKd(near, x, y, best);

        // Only explore the far side if the splitting plane is within best distance.
        if (diff * diff < best.distSq) {
            nearestKd(far, x, y, best);
        }
    }

    private static final class Best {
        private FMGCell cell;
        private double distSq;
    }

    private static final class KdNode {
        private final FMGCell cell;
        private final int axis;
        private KdNode left;
        private KdNode right;

        private KdNode(FMGCell cell, int axis) {
            this.cell = cell;
            this.axis = axis;
        }
    }

    /**
     * Returns up to {@code count} cells ordered by proximity to the provided coordinate.
     * The caller can use this to perform smooth interpolation across the Voronoi field.
     */
    public List<FMGCell> findNearestCells(double x, double y, int count) {
        if (cells == null || cells.isEmpty() || count <= 0) {
            return Collections.emptyList();
        }

        List<FMGCellDistance> nearest = new ArrayList<>(count);

        for (FMGCell cell : cells) {
            double dx = cell.getPx() - x;
            double dy = cell.getPy() - y;
            double distSq = dx * dx + dy * dy;

            if (nearest.size() < count) {
                nearest.add(new FMGCellDistance(cell, distSq));
                continue;
            }

            int idx = findFarthestIndex(nearest);
            if (distSq < nearest.get(idx).distanceSq) {
                nearest.set(idx, new FMGCellDistance(cell, distSq));
            }
        }

        nearest.sort(Comparator.comparingDouble(entry -> entry.distanceSq));

        List<FMGCell> ordered = new ArrayList<>(nearest.size());
        for (FMGCellDistance entry : nearest) {
            ordered.add(entry.cell);
        }
        return ordered;
    }

    private static int findFarthestIndex(List<FMGCellDistance> entries) {
        double max = -1;
        int index = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).distanceSq > max) {
                max = entries.get(i).distanceSq;
                index = i;
            }
        }
        return index;
    }

    private static final class FMGCellDistance {
        private final FMGCell cell;
        private final double distanceSq;

        private FMGCellDistance(FMGCell cell, double distanceSq) {
            this.cell = cell;
            this.distanceSq = distanceSq;
        }
    }

    private void rebuildCellProvinceIndex() {
        cellToProvince.clear();

        if (provinces != null) {
            for (FMGProvince province : provinces) {
                if (province == null) continue;
                if (province.getCells() == null) continue;
                for (int cellId : province.getCells()) {
                    cellToProvince.put(cellId, province.getI());
                }
            }
        }

        if (cells != null) {
            for (FMGCell cell : cells) {
                if (cell == null) continue;
                int provinceId = cell.getProvince();
                if (provinceId >= 0) {
                    cellToProvince.putIfAbsent(cell.getI(), provinceId);
                }
            }
        }
    }
}
