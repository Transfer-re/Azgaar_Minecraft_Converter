package com.example.fmg;

import java.util.Map;

/**
 * A single cell (Voronoi polygon) from the FMG map.
 * Each cell represents a terrain region with properties like elevation, biome, etc.
 */
public class FMGCell {
    private int i;           // Cell ID
    private double[] p;      // Position [x, y]
    private int[] v;         // Vertex indices around the cell (pack.cells.v)
    private int[] c;         // Neighboring cell indices (pack.cells.c)
    private int h;           // Height/elevation
    private int biome;       // Biome ID
    private int fl;          // Flux (water flow)
    private int r;           // River ID
    private Map<String, Integer> routes; // Road connections to other cells
    private int burg;        // City/burg ID (0 if no city)
    private int state;       // State/kingdom ID
    private double area;     // Cell area
    private double t;        // Temperature
    
    // Getters and setters
    public int getI() { return i; }
    public void setI(int i) { this.i = i; }
    
    public double[] getP() { return p; }
    public void setP(double[] p) { this.p = p; }
    
    public double getPx() { return p != null && p.length > 0 ? p[0] : 0; }
    public double getPy() { return p != null && p.length > 1 ? p[1] : 0; }

    public int[] getV() { return v; }
    public void setV(int[] v) { this.v = v; }

    public int[] getC() { return c; }
    public void setC(int[] c) { this.c = c; }
    
    public int getH() { return h; }
    public void setH(int h) { this.h = h; }
    
    public int getBiome() { return biome; }
    public void setBiome(int biome) { this.biome = biome; }
    
    public int getFl() { return fl; }
    public void setFl(int fl) { this.fl = fl; }
    
    public int getR() { return r; }
    public void setR(int r) { this.r = r; }
    
    public Map<String, Integer> getRoutes() { return routes; }
    public void setRoutes(Map<String, Integer> routes) { this.routes = routes; }
    
    public int getBurg() { return burg; }
    public void setBurg(int burg) { this.burg = burg; }
    
    public int getState() { return state; }
    public void setState(int state) { this.state = state; }
    
    public double getArea() { return area; }
    public void setArea(double area) { this.area = area; }
    
    public double getT() { return t; }
    public void setT(double t) { this.t = t; }
}
