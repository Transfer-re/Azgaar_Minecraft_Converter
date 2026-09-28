package com.example.surface;

/**
 * Hashing and noise for the layered surface. A line-for-line port of the
 * review page's JavaScript ({@code tools/fmgtrees/palette_preview.html}), so a
 * palette behaves in the game the way it did on the page. Minecraft-free.
 */
public final class SurfaceNoise {

    private SurfaceNoise() {
    }

    /** 32-bit integer hash of three ints, returned unsigned in a long. */
    public static long h32(int a, int b, int c) {
        int h = (a * 0x27d4eb2d) ^ (b * 0x165667b1) ^ (c * 0x9e3779b1);
        h = (h ^ (h >>> 15)) * 0x85ebca6b;
        h = (h ^ (h >>> 13)) * 0xc2b2ae35;
        return (h ^ (h >>> 16)) & 0xFFFFFFFFL;
    }

    /** Hash in [0, 1). */
    public static double u01(int a, int b, int c) {
        return h32(a, b, c) / 4294967296.0;
    }

    /** Smooth value noise in [0, 1]. */
    public static double vnoise(double x, double z, int s) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = x - x0, fz = z - z0;
        double sx = fx * fx * (3 - 2 * fx), sz = fz * fz * (3 - 2 * fz);
        double a = u01(x0, z0, s), b = u01(x0 + 1, z0, s);
        double c = u01(x0, z0 + 1, s), d = u01(x0 + 1, z0 + 1, s);
        return a + (b - a) * sx + (c - a) * sz + (a - b - c + d) * sx * sz;
    }

    /** Index into {@code weights} for a roll in [0, 1). */
    public static int weighted(double r, double[] weights, double total) {
        double t = r * total;
        for (int i = 0; i < weights.length; i++) {
            if (t < weights[i]) return i;
            t -= weights[i];
        }
        return weights.length - 1;
    }

    /**
     * Warped cellular pick: jittered-grid Voronoi cells of size {@code scale},
     * each drawing an index by weight, sampled at a noise-displaced position.
     * Gives patches in the right proportions with organic edges -- thresholded
     * value noise does not, because its values bunch around the middle.
     */
    public static int cellPick(double x, double z, double scale, int seed, double[] weights, double total) {
        double wx = x + (vnoise(x / scale * 1.7, z / scale * 1.7, seed + 11) - 0.5) * scale * 0.9;
        double wz = z + (vnoise(x / scale * 1.7 + 31.4, z / scale * 1.7 - 12.7, seed + 13) - 0.5) * scale * 0.9;
        int gx = (int) Math.floor(wx / scale), gz = (int) Math.floor(wz / scale);
        double best = Double.MAX_VALUE, pick = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = gx + dx, cz = gz + dz;
                double px = (cx + u01(cx, cz, seed + 1)) * scale;
                double pz = (cz + u01(cx, cz, seed + 2)) * scale;
                double d = (px - wx) * (px - wx) + (pz - wz) * (pz - wz);
                if (d < best) {
                    best = d;
                    pick = u01(cx, cz, seed + 3);
                }
            }
        }
        return weighted(pick, weights, total);
    }

    /** The page's string seed: {@code [...id].reduce((a, c) => a*31 + c, 17)}. */
    public static int seedOf(String id) {
        int a = 17;
        for (int i = 0; i < id.length(); i++) {
            a = a * 31 + id.charAt(i);
        }
        return a;
    }

    /**
     * Horizontal displacement for biome/palette lookups, so region borders
     * wander instead of following the straight edges of the map's cells.
     * Returns {dx, dz} in blocks, each within +/- 1.3 * amplitude.
     */
    public static double[] warp(double amplitude, int x, int z) {
        double dx = (vnoise(x / 64.0, z / 64.0, 0x5EED1) - 0.5) * 2.0
                + (vnoise(x / 17.0, z / 17.0, 0x5EED3) - 0.5) * 0.6;
        double dz = (vnoise(x / 64.0 + 91.7, z / 64.0 - 33.1, 0x5EED2) - 0.5) * 2.0
                + (vnoise(x / 17.0 - 7.3, z / 17.0 + 55.9, 0x5EED4) - 0.5) * 0.6;
        return new double[] {dx * amplitude, dz * amplitude};
    }
}
