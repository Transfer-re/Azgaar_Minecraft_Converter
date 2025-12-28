package com.example.fmg;

import java.util.List;

/**
 * Samples FMG routes (roads and trails) at world coordinates.
 *
 * This mirrors the world-to-FMG transform used by FMGHeightSampler
 * and FMGBiomeMapper so routes line up with the heightfield and
 * biome projection.
 */
public final class FMGRouteSampler {

    public enum RouteKind {
        NONE,
        TRAIL,
        ROAD
    }

    private FMGRouteSampler() {}

    public static RouteKind sampleRoute(FMGMapData map, int worldX, int worldZ) {
        if (map == null || map.getInfo() == null || map.getRoutes() == null || map.getRoutes().isEmpty()) {
            return RouteKind.NONE;
        }

        double mapW = map.getInfo().getWidth();
        double mapH = map.getInfo().getHeight();

        double offsetX = -(mapW * FMGHeightSampler.SAMPLE_SCALE) / 2.0;
        double offsetZ = -(mapH * FMGHeightSampler.SAMPLE_SCALE) / 2.0;

        double fmgX = (worldX - offsetX) / FMGHeightSampler.SAMPLE_SCALE;
        double fmgY = (worldZ - offsetZ) / FMGHeightSampler.SAMPLE_SCALE;

        if (fmgX < 0 || fmgY < 0 || fmgX >= mapW || fmgY >= mapH) {
            return RouteKind.NONE;
        }

        RouteKind bestKind = RouteKind.NONE;
        double bestScore = Double.POSITIVE_INFINITY;

        for (FMGRoute route : map.getRoutes()) {
            if (route == null || route.getGroup() == null) continue;

            String group = route.getGroup();
            RouteKind kind;
            double threshold;

            if ("trails".equals(group)) {
                kind = RouteKind.TRAIL;
                threshold = 0.35; // ~2 blocks wide in world space
            } else if ("roads".equals(group)) {
                kind = RouteKind.ROAD;
                threshold = 0.6;  // ~3.5 blocks wide in world space
            } else {
                continue;
            }

            List<FMGRoute.FMGRoutePoint> pts = route.getPoints();
            if (pts == null || pts.size() < 2) {
                continue;
            }

            double bestForRoute = distanceToPolyline(fmgX, fmgY, pts);
            if (bestForRoute < 0) continue;

            double score = bestForRoute / threshold;
            if (score <= 1.0 && score < bestScore) {
                bestScore = score;
                bestKind = kind;
            }
        }

        return bestKind;
    }

    private static double distanceToPolyline(double px, double py, List<FMGRoute.FMGRoutePoint> pts) {
        double best = Double.POSITIVE_INFINITY;

        for (int i = 0; i < pts.size() - 1; i++) {
            FMGRoute.FMGRoutePoint a = pts.get(i);
            FMGRoute.FMGRoutePoint b = pts.get(i + 1);

            double d = distancePointToSegment(px, py, a.getX(), a.getY(), b.getX(), b.getY());
            if (d < best) {
                best = d;
            }
        }

        return best == Double.POSITIVE_INFINITY ? -1.0 : best;
    }

    private static double distancePointToSegment(
            double px,
            double py,
            double ax,
            double ay,
            double bx,
            double by
    ) {
        double vx = bx - ax;
        double vy = by - ay;
        double wx = px - ax;
        double wy = py - ay;

        double segLenSq = vx * vx + vy * vy;
        if (segLenSq <= 0.0) {
            // A and B are the same point
            double dx = px - ax;
            double dy = py - ay;
            return Math.sqrt(dx * dx + dy * dy);
        }

        double t = (wx * vx + wy * vy) / segLenSq;
        if (t <= 0.0) {
            double dx = px - ax;
            double dy = py - ay;
            return Math.sqrt(dx * dx + dy * dy);
        }
        if (t >= 1.0) {
            double dx = px - bx;
            double dy = py - by;
            return Math.sqrt(dx * dx + dy * dy);
        }

        double projX = ax + t * vx;
        double projY = ay + t * vy;
        double dx = px - projX;
        double dy = py - projY;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
