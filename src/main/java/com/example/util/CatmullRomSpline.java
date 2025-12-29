package com.example.util;

import java.util.ArrayList;
import java.util.List;

public final class CatmullRomSpline {

    private CatmullRomSpline() {}

    /** Simple 2D vector */
    public record Vec2(double x, double z) {}

    /**
     * Samples a Catmull–Rom spline through the given control points.
     * @param control input points (world XZ)
     * @param step distance between samples in blocks (e.g. 4.0)
     */
    public static List<Vec2> sample(List<Vec2> control, double step) {
        List<Vec2> out = new ArrayList<>();
        if (control.size() < 2) return out;

        for (int i = 0; i < control.size() - 1; i++) {
            Vec2 p0 = i > 0 ? control.get(i - 1) : control.get(i);
            Vec2 p1 = control.get(i);
            Vec2 p2 = control.get(i + 1);
            Vec2 p3 = (i + 2 < control.size()) ? control.get(i + 2) : p2;

            double segLen = distance(p1, p2);
            int steps = Math.max(1, (int)(segLen / step));

            for (int j = 0; j <= steps; j++) {
                double t = j / (double) steps;
                out.add(catmull(p0, p1, p2, p3, t));
            }
        }
        return out;
    }

    private static Vec2 catmull(Vec2 p0, Vec2 p1, Vec2 p2, Vec2 p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;

        double x =
            0.5 * ((2 * p1.x) +
            (-p0.x + p2.x) * t +
            (2*p0.x - 5*p1.x + 4*p2.x - p3.x) * t2 +
            (-p0.x + 3*p1.x - 3*p2.x + p3.x) * t3);

        double z =
            0.5 * ((2 * p1.z) +
            (-p0.z + p2.z) * t +
            (2*p0.z - 5*p1.z + 4*p2.z - p3.z) * t2 +
            (-p0.z + 3*p1.z - 3*p2.z + p3.z) * t3);

        return new Vec2(x, z);
    }

    private static double distance(Vec2 a, Vec2 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx*dx + dz*dz);
    }
}
