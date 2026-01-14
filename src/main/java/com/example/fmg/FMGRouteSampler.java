package com.example.fmg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.util.CatmullRomSpline;

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

    /**
     * A sampled point on a route spline, in FMG coordinate space.
     */
    public static final class RouteSample {
        private final double x;
        private final double y;

        RouteSample(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }
    }

    private static final Map<Integer, List<RouteSample>> SAMPLED_ROUTES = new HashMap<>();

    /**
     * Returns the sampled spline points for a route id, in FMG-space.
     *
     * <p>Call {@link #build(FMGMapData)} before using this.</p>
     */
    public static List<RouteSample> getSampledRoute(int routeId) {
        return SAMPLED_ROUTES.get(routeId);
    }


    public static void build(FMGMapData map) {
        SAMPLED_ROUTES.clear();

        if (map == null || map.getRoutes() == null) return;

        for (FMGRoute route : map.getRoutes()) {
            List<FMGRoute.FMGRoutePoint> pts = route.getPoints();
            if (pts == null || pts.size() < 2) continue;

            // Convert to Vec2 for spline
            List<CatmullRomSpline.Vec2> control = new ArrayList<>();
            for (FMGRoute.FMGRoutePoint p : pts) {
                control.add(new CatmullRomSpline.Vec2(p.getX(), p.getY()));
            }

                // Sample spline (FMG-space!)
                // FMGHeightSampler.SAMPLE_SCALE is 10.0, so 0.10 FMG-units ~= 1 world block.
                // Denser sampling makes the 1D slope relaxation behave predictably and avoids
                // "2 blocks up at once" artifacts when projecting samples back to world-grid.
                List<CatmullRomSpline.Vec2> sampled =
                    CatmullRomSpline.sample(control, 0.10);

            List<RouteSample> samples = new ArrayList<>(sampled.size());
            for (var v : sampled) {
                samples.add(new RouteSample(v.x(), v.z()));
            }

            SAMPLED_ROUTES.put(route.getI(), samples);
        }
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

            List<RouteSample> samples = SAMPLED_ROUTES.get(route.getI());
            if (samples == null || samples.size() < 2) continue;

            double bestForRoute = distanceToSampledPolyline(fmgX, fmgY, samples);
            if (bestForRoute < 0) continue;

            double score = bestForRoute / threshold;
            if (score <= 1.0 && score < bestScore) {
                bestScore = score;
                bestKind = kind;
            }
        }

        return bestKind;
    }

    private static double distanceToSampledPolyline(
            double px,
            double py,
            List<RouteSample> samples
    ) {
        double best = Double.POSITIVE_INFINITY;

        for (int i = 0; i < samples.size() - 1; i++) {
            RouteSample a = samples.get(i);
            RouteSample b = samples.get(i + 1);

            double d = distancePointToSegment(
                    px, py,
                    a.x, a.y,
                    b.x, b.y
            );
            if (d < best) best = d;
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
