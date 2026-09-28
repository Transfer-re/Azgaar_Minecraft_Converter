package com.example.terrain;

import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * Seeded 2-D Perlin noise plus the raster fields the terrain bake is built
 * from. Field frequencies are in cycles per raster cell, wavelengths in blocks
 * (the refinemap/terrain.py workbench uses the same recipes).
 */
public final class TerrainNoise {

    private static final double[] GX = new double[16];
    private static final double[] GY = new double[16];

    static {
        for (int i = 0; i < 16; i++) {
            GX[i] = Math.cos(i * Math.PI / 8);
            GY[i] = Math.sin(i * Math.PI / 8);
        }
    }

    private final int[] perm = new int[514];

    public TerrainNoise(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        SplittableRandom r = new SplittableRandom(seed);
        for (int i = 255; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 514; i++) perm[i] = p[i & 255];
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double grad(int h, double x, double y) {
        int g = h & 15;
        return GX[g] * x + GY[g] * y;
    }

    /** Perlin noise, roughly [-1, 1]. */
    public double perlin(double x, double y) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        double xf = x - xi;
        double yf = y - yi;
        xi &= 255;
        yi &= 255;
        double u = fade(xf);
        double v = fade(yf);
        int aa = perm[perm[xi] + yi];
        int ab = perm[perm[xi] + yi + 1];
        int ba = perm[perm[xi + 1] + yi];
        int bb = perm[perm[xi + 1] + yi + 1];
        double x1 = grad(aa, xf, yf) + u * (grad(ba, xf - 1, yf) - grad(aa, xf, yf));
        double x2 = grad(ab, xf, yf - 1) + u * (grad(bb, xf - 1, yf - 1) - grad(ab, xf, yf - 1));
        return (x1 + v * (x2 - x1)) * 1.41;
    }

    /** fBm over raster cells: freq in cycles per cell. */
    public float[] fbm(int w, int h, double freq, int octaves, double ox, double oy) {
        float[] out = new float[w * h];
        IntStream.range(0, h).parallel().forEach(j -> {
            for (int i = 0; i < w; i++) {
                double f = freq, a = 1, s = 0, n = 0;
                for (int o = 0; o < octaves; o++) {
                    s += a * perlin(i * f + ox + o * 17.3, j * f + oy + o * 31.7);
                    n += a;
                    a *= 0.5;
                    f *= 2;
                }
                out[j * w + i] = (float) (s / n);
            }
        });
        return out;
    }

    /** Domain-warped ridged multifractal (warp fields in [-1, 1], warp in cells). */
    public float[] ridged(int w, int h, double freq, int octaves, float[] wx, float[] wy, double warp, double gain) {
        float[] out = new float[w * h];
        IntStream.range(0, h).parallel().forEach(j -> {
            for (int i = 0; i < w; i++) {
                int k = j * w + i;
                double x = i + wx[k] * warp;
                double y = j + wy[k] * warp;
                double f = freq, a = 1, weight = 1, s = 0, norm = 0;
                for (int o = 0; o < octaves; o++) {
                    double n = 1 - Math.abs(perlin(x * f + o * 57.1, y * f + o * 91.3));
                    n = n * n * weight;
                    weight = Math.min(1, Math.max(0, n * gain));
                    s += n * a;
                    norm += a;
                    a *= 0.5;
                    f *= 2;
                }
                out[k] = (float) (s / norm);
            }
        });
        return out;
    }

    /** fBm stretched along {@code angle}; wavelengths in blocks, dx = blocks per cell. */
    public float[] aniso(int w, int h, double dx, double wlU, double wlV, double angle, int octaves, double off) {
        float[] out = new float[w * h];
        double ca = Math.cos(angle), sa = Math.sin(angle);
        IntStream.range(0, h).parallel().forEach(j -> {
            for (int i = 0; i < w; i++) {
                double x = i * dx, y = j * dx;
                double u = (x * ca + y * sa) / wlU;
                double v = (-x * sa + y * ca) / wlV;
                double s = 0, a = 1, n = 0, f = 1;
                for (int o = 0; o < octaves; o++) {
                    s += a * perlin(u * f + off + o * 17.3, v * f - off + o * 31.7);
                    n += a;
                    a *= 0.5;
                    f *= 2;
                }
                out[j * w + i] = (float) (s / n);
            }
        });
        return out;
    }

    /**
     * Transverse dunes: gentle windward slope, steep lee, sinuous crests broken
     * into segments. 0 (interdune flat) .. ~1 (crest).
     */
    public float[] dunes(int w, int h, double dx, double wl, double angle) {
        float[] out = new float[w * h];
        double ca = Math.cos(angle), sa = Math.sin(angle);
        IntStream.range(0, h).parallel().forEach(j -> {
            for (int i = 0; i < w; i++) {
                double x = i * dx, y = j * dx;
                double u = x * ca + y * sa;
                double v = -x * sa + y * ca;
                double warp = perlin(u / (wl * 4) + 3.1, v / (wl * 4) + 50) * 0.7
                        + perlin(u / (wl * 1.3) + 20, v / (wl * 1.3)) * 0.2;
                double s = v / wl + warp;
                double k = Math.floor(s);
                double fr = s - k;
                double p = fr < 0.7 ? fr / 0.7 : (1 - fr) / 0.3;
                p = p * p * (3 - 2 * p);
                double seg = Math.max(0, 0.55 + 0.6 * perlin(u / (wl * 1.8) + 7, k * 3.1 + 0.5));
                out[j * w + i] = (float) (p * seg);
            }
        });
        return out;
    }
}
