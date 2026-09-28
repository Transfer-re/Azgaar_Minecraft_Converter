package com.example.surface;

/**
 * The terrain variables a palette reads, computed the same way in the game
 * ({@code LayeredSurface}) and in the harness ({@link SurfaceCli}).
 * Minecraft-free.
 */
public final class SurfaceVars {

    /** Shore distances are searched this far; beyond it they read as infinite. */
    public static final int SHORE_RADIUS = 8;

    public static final byte LAND = 0, SEA = 1, FRESH = 2;

    private SurfaceVars() {
    }

    /**
     * The continuous height, kept within the column's own block: a layer never
     * climbs into the block above the integer terrain or digs out the one below.
     * {@code h} is the first air Y (top solid block at {@code h - 1}).
     */
    public static double clampExact(double exact, int h) {
        return Math.max(h - 1 + 1.0 / 16.0, Math.min(h + 15.0 / 16.0, exact));
    }

    /** Gradient magnitude from the four neighbours' clamped heights (1.0 = 45 degrees). */
    public static double slope(double east, double west, double south, double north) {
        return Math.hypot((east - west) / 2.0, (south - north) / 2.0);
    }

    /** World height -> Azgaar height units: 19 at sea level, 100 at {@code top}. */
    public static double altitude(double y, int seaLevel, double top) {
        double t = (y - seaLevel) / Math.max(1.0, top - seaLevel);
        return 19.0 + 81.0 * Math.max(0.0, Math.min(1.0, t));
    }

    /**
     * Chamfer distance (1 / sqrt 2 steps) from every cell of an {@code n x n}
     * grid, indexed {@code x * n + z}, to the nearest cell of kind {@code src}.
     */
    public static float[] chamfer(byte[] kind, int n, byte src) {
        float[] d = new float[n * n];
        for (int i = 0; i < d.length; i++) d[i] = kind[i] == src ? 0f : 1e9f;
        final float diag = 1.41421356f;
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                int i = x * n + z;
                if (x > 0) d[i] = Math.min(d[i], d[i - n] + 1);
                if (z > 0) d[i] = Math.min(d[i], d[i - 1] + 1);
                if (x > 0 && z > 0) d[i] = Math.min(d[i], d[i - n - 1] + diag);
                if (x > 0 && z < n - 1) d[i] = Math.min(d[i], d[i - n + 1] + diag);
            }
        }
        for (int x = n - 1; x >= 0; x--) {
            for (int z = n - 1; z >= 0; z--) {
                int i = x * n + z;
                if (x < n - 1) d[i] = Math.min(d[i], d[i + n] + 1);
                if (z < n - 1) d[i] = Math.min(d[i], d[i + 1] + 1);
                if (x < n - 1 && z < n - 1) d[i] = Math.min(d[i], d[i + n + 1] + diag);
                if (x < n - 1 && z > 0) d[i] = Math.min(d[i], d[i + n - 1] + diag);
            }
        }
        return d;
    }

    /** A chamfer distance as a palette variable: infinite past {@link #SHORE_RADIUS}. */
    public static float capped(float d) {
        return d > SHORE_RADIUS ? Float.POSITIVE_INFINITY : d;
    }
}
