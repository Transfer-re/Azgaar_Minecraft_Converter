    package com.example.fmg;

    /**
     * A biome definition from the FMG map
     */
    public class FMGBiome {
        private int i;        // Biome ID
        private String name;  // Biome name
        private String color; // Biome color (optional)
        
        // Getters and setters
        public int getI() { return i; }
        public void setI(int i) { this.i = i; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
    }
