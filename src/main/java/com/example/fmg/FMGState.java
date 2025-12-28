package com.example.fmg;

/**
 * A state/kingdom from the FMG map
 */
public class FMGState {
    private int i;        // State ID
    private String name;  // State name
    private int capital;  // Capital city ID
    
    // Getters and setters
    public int getI() { return i; }
    public void setI(int i) { this.i = i; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public int getCapital() { return capital; }
    public void setCapital(int capital) { this.capital = capital; }
}
