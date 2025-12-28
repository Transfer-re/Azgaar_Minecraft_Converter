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

        // Extended FMG burg attributes for integration with city generator
        private int culture;     // Burg culture id
        private int state;       // Burg state id
        private int feature;     // Landmass / feature id
        private String type;     // Burg type (e.g. town, city, capital)
        private int mfcg;        // Medieval Fantasy City Generator seed
        private String link;     // Optional explicit MFCG link
        private int port;        // 0 if not a port, otherwise feature id of the water body
        private int citadel;     // 1 if has castle
        private int plaza;       // 1 if has marketplace
        private int shanty;      // 1 if has shanty town
        private int temple;      // 1 if has temple
        private int walls;       // 1 if has walls
        private boolean lock;    // True if locked (not affected by regeneration)
        private boolean removed; // True if burg is removed
        
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

        public int getCulture() { return culture; }
        public void setCulture(int culture) { this.culture = culture; }

        public int getState() { return state; }
        public void setState(int state) { this.state = state; }

        public int getFeature() { return feature; }
        public void setFeature(int feature) { this.feature = feature; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public int getMfcg() { return mfcg; }
        public void setMfcg(int mfcg) { this.mfcg = mfcg; }

        public String getLink() { return link; }
        public void setLink(String link) { this.link = link; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public int getCitadel() { return citadel; }
        public void setCitadel(int citadel) { this.citadel = citadel; }

        public int getPlaza() { return plaza; }
        public void setPlaza(int plaza) { this.plaza = plaza; }

        public int getShanty() { return shanty; }
        public void setShanty(int shanty) { this.shanty = shanty; }

        public int getTemple() { return temple; }
        public void setTemple(int temple) { this.temple = temple; }

        public int getWalls() { return walls; }
        public void setWalls(int walls) { this.walls = walls; }

        public boolean isLock() { return lock; }
        public void setLock(boolean lock) { this.lock = lock; }

        public boolean isRemoved() { return removed; }
        public void setRemoved(boolean removed) { this.removed = removed; }
    }
