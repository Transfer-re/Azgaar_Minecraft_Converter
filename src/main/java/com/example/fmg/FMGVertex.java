package com.example.fmg;

/**
 * A single Voronoi vertex from the FMG "pack.vertices" array.
 * Vertices are used to identify precise land/ocean borders
 * by checking which cells meet at each vertex.
 */
public class FMGVertex {

    private int i;        // Vertex ID
    private double[] p;   // Position [x, y]
    private int[] c;      // Adjacent cell indices
    private int[] v;      // Adjacent vertex indices

    public int getI() { return i; }
    public void setI(int i) { this.i = i; }

    public double[] getP() { return p; }
    public void setP(double[] p) { this.p = p; }

    public double getPx() { return p != null && p.length > 0 ? p[0] : 0; }
    public double getPy() { return p != null && p.length > 1 ? p[1] : 0; }

    public int[] getC() { return c; }
    public void setC(int[] c) { this.c = c; }

    public int[] getV() { return v; }
    public void setV(int[] v) { this.v = v; }
}
