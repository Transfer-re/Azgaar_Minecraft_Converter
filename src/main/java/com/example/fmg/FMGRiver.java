package com.example.fmg;

import java.util.List;

/**
 * A river from the FMG map.
 */
public class FMGRiver {
    private int i;                  // River id
    private String name;            // River name
    private String type;            // River type
    private int source;             // Source cell id
    private int mouth;              // Mouth cell id
    private int parent;             // Parent river id
    private int basin;              // Basin id (main stem id)
    private int[] cells;            // Sequence of cells along the river
    private List<double[]> points;  // Optional coordinates [x, y]
    private double discharge;       // Flux in m3/s
    private double length;          // Length in km
    private double width;           // Mouth width in km
    private double sourceWidth;     // Additional width at the source

    public int getI() { return i; }
    public void setI(int i) { this.i = i; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public int getSource() { return source; }
    public void setSource(int source) { this.source = source; }

    public int getMouth() { return mouth; }
    public void setMouth(int mouth) { this.mouth = mouth; }

    public int getParent() { return parent; }
    public void setParent(int parent) { this.parent = parent; }

    public int getBasin() { return basin; }
    public void setBasin(int basin) { this.basin = basin; }

    public int[] getCells() { return cells; }
    public void setCells(int[] cells) { this.cells = cells; }

    public List<double[]> getPoints() { return points; }
    public void setPoints(List<double[]> points) { this.points = points; }

    public double getDischarge() { return discharge; }
    public void setDischarge(double discharge) { this.discharge = discharge; }

    public double getLength() { return length; }
    public void setLength(double length) { this.length = length; }

    public double getWidth() { return width; }
    public void setWidth(double width) { this.width = width; }

    public double getSourceWidth() { return sourceWidth; }
    public void setSourceWidth(double sourceWidth) { this.sourceWidth = sourceWidth; }
}
