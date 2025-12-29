package com.example.fmg;

import java.util.List;

public class FMGProvince {
    private int i;
    private String name;
    private int state;
    private String color;
    private List<Integer> cells; // <-- THIS is key
    
    public int getI() { return i; }
    public void setI(int i) { this.i = i; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getState() { return state; }
    public void setState(int state) { this.state = state; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public List<Integer> getCells() { return cells; }
    public void setCells(List<Integer> cells) { this.cells = cells; }
}