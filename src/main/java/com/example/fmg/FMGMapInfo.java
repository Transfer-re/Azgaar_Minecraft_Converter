package com.example.fmg;

/**
 * Top-level map information from FMG JSON
 */
public class FMGMapInfo {
    private String version;
    private String mapName;
    private int width;
    private int height;
    private String seed;
    
    // Getters and setters
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    
    public String getMapName() { return mapName; }
    public void setMapName(String mapName) { this.mapName = mapName; }
    
    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }
    
    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }
    
    public String getSeed() { return seed; }
    public void setSeed(String seed) { this.seed = seed; }
}
