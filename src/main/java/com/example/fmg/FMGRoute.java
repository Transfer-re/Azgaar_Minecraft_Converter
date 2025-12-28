package com.example.fmg;

import java.util.List;

/**
 * A land route (road or trail) from the FMG map.
 *
 * Routes are stored globally in pack.routes and reference
 * control points in FMG coordinate space.
 */
public class FMGRoute {
    private int i;                  // Route id
    private String group;           // Route group: roads, trails, searoutes
    private int feature;            // Feature id the route belongs to
    private List<FMGRoutePoint> points; // Control points [x, y, cellId]
    private String name;            // Optional route name
    private boolean lock;           // True if route is locked

    public int getI() { return i; }
    public void setI(int i) { this.i = i; }

    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }

    public int getFeature() { return feature; }
    public void setFeature(int feature) { this.feature = feature; }

    public List<FMGRoutePoint> getPoints() { return points; }
    public void setPoints(List<FMGRoutePoint> points) { this.points = points; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isLock() { return lock; }
    public void setLock(boolean lock) { this.lock = lock; }

    /**
     * Single control point on a route polyline.
     */
    public static class FMGRoutePoint {
        private double x;
        private double y;
        private int cellId;

        public FMGRoutePoint(double x, double y, int cellId) {
            this.x = x;
            this.y = y;
            this.cellId = cellId;
        }

        public double getX() { return x; }
        public void setX(double x) { this.x = x; }

        public double getY() { return y; }
        public void setY(double y) { this.y = y; }

        public int getCellId() { return cellId; }
        public void setCellId(int cellId) { this.cellId = cellId; }
    }
}
