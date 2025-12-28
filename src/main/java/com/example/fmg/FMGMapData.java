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
    
    // Lookup maps for fast access
    private Map<Integer, FMGCell> cellMap;
    private Map<Integer, FMGBurg> burgMap;
    private Map<Integer, FMGBiome> biomeMap;
    private Map<Integer, FMGState> stateMap;
    
    public FMGMapData() {
        this.cellMap = new HashMap<>();
        this.burgMap = new HashMap<>();
        this.biomeMap = new HashMap<>();
        this.stateMap = new HashMap<>();
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
    
    // Lookup methods for fast access
    public FMGCell getCell(int id) { return cellMap.get(id); }
    public FMGBurg getBurg(int id) { return burgMap.get(id); }
    public FMGBiome getBiome(int id) { return biomeMap.get(id); }
    public FMGState getState(int id) { return stateMap.get(id); }
    
    // Helper to find the nearest cell to a coordinate
    public FMGCell findNearestCell(double x, double y) {
        FMGCell nearest = null;
        double minDist = Double.MAX_VALUE;
        
        for (FMGCell cell : cells) {
            double dx = cell.getPx() - x;
            double dy = cell.getPy() - y;
            double dist = dx * dx + dy * dy; // Squared distance (faster)
            
            if (dist < minDist) {
                minDist = dist;
                nearest = cell;
            }
        }
        
        return nearest;
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
}
