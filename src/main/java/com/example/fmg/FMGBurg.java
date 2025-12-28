    package com.example.fmg;

    /**
     * A city/town (burg) from the FMG map
     */
    public class FMGBurg {
        private int i;           // Burg ID
        private String name;     // City name
        private int cell;        // Cell ID where city is located
        private int population;  // Population
        private int capital;     // 1 if capital, 0 if not
        private double x;        // X coordinate
        private double y;        // Y coordinate
        
        // Getters and setters
        public int getI() { return i; }
        public void setI(int i) { this.i = i; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public int getCell() { return cell; }
        public void setCell(int cell) { this.cell = cell; }
        
        public int getPopulation() { return population; }
        public void setPopulation(int population) { this.population = population; }
        
        public int getCapital() { return capital; }
        public void setCapital(int capital) { this.capital = capital; }
        
        public boolean isCapital() { return capital == 1; }
        
        public double getX() { return x; }
        public void setX(double x) { this.x = x; }
        
        public double getY() { return y; }
        public void setY(double y) { this.y = y; }
    }
