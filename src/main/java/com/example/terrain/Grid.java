package com.example.terrain;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Row-major raster helpers ({@code a[y * w + x]}) for the terrain bake: blur,
 * resize, exact distance transform, sampling. No Minecraft dependency.
 */
public final class Grid {

    private Grid() {
    }

    public static double smoothstep(double e0, double e1, double x) {
        if (e1 == e0) return x < e0 ? 0 : 1;
        double t = (x - e0) / (e1 - e0);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Quadratic smooth minimum; never above {@code min(a, b)}. */
    public static double smin(double a, double b, double k) {
        double h = Math.max(k - Math.abs(a - b), 0) / k;
        return Math.min(a, b) - h * h * k * 0.25;
    }

    // ---- blur --------------------------------------------------------

    /** Separable gaussian, reflect-101 borders (cv2.GaussianBlur's default). */
    public static float[] gaussian(float[] src, int w, int h, double sigma) {
        if (sigma <= 0) return src.clone();
        int r = Math.max(1, (int) Math.ceil(sigma * 3.0));
        float[] k = new float[2 * r + 1];
        double sum = 0;
        for (int i = -r; i <= r; i++) {
            k[i + r] = (float) Math.exp(-0.5 * i * i / (sigma * sigma));
            sum += k[i + r];
        }
        for (int i = 0; i < k.length; i++) k[i] /= (float) sum;

        float[] tmp = new float[src.length];
        IntStream.range(0, h).parallel().forEach(y -> {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                double s = 0;
                for (int i = -r; i <= r; i++) s += k[i + r] * src[row + reflect(x + i, w)];
                tmp[row + x] = (float) s;
            }
        });
        float[] out = new float[src.length];
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                double s = 0;
                for (int i = -r; i <= r; i++) s += k[i + r] * tmp[reflect(y + i, h) * w + x];
                out[y * w + x] = (float) s;
            }
        });
        return out;
    }

    /** Gaussian of {@code v} counting only {@code mask} pixels (0 where nothing is near). */
    public static float[] maskedGaussian(float[] v, boolean[] mask, int w, int h, double sigma) {
        float[] vm = new float[v.length];
        float[] m = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            if (mask[i]) {
                vm[i] = v[i];
                m[i] = 1;
            }
        }
        float[] num = gaussian(vm, w, h, sigma);
        float[] den = gaussian(m, w, h, sigma);
        for (int i = 0; i < v.length; i++) num[i] = den[i] > 1e-4f ? num[i] / Math.max(den[i], 1e-4f) : 0f;
        return num;
    }

    private static int reflect(int i, int n) {
        if (n == 1) return 0;
        while (i < 0 || i >= n) {
            if (i < 0) i = -i;
            if (i >= n) i = 2 * n - 2 - i;
        }
        return i;
    }

    // ---- resize ------------------------------------------------------

    /** Bicubic resize (Keys, a = -0.75, pixel-centre aligned -- cv2.INTER_CUBIC). */
    public static float[] resizeCubic(float[] src, int sw, int sh, int dw, int dh) {
        float[] out = new float[dw * dh];
        double fx = sw / (double) dw;
        double fy = sh / (double) dh;
        IntStream.range(0, dh).parallel().forEach(y -> {
            double sy = (y + 0.5) * fy - 0.5;
            int iy = (int) Math.floor(sy);
            double ty = sy - iy;
            double[] wy = keys(ty);
            for (int x = 0; x < dw; x++) {
                double sx = (x + 0.5) * fx - 0.5;
                int ix = (int) Math.floor(sx);
                double[] wx = keys(sx - ix);
                double s = 0;
                for (int j = 0; j < 4; j++) {
                    int yy = Math.min(sh - 1, Math.max(0, iy - 1 + j));
                    double rs = 0;
                    for (int i = 0; i < 4; i++) {
                        int xx = Math.min(sw - 1, Math.max(0, ix - 1 + i));
                        rs += wx[i] * src[yy * sw + xx];
                    }
                    s += wy[j] * rs;
                }
                out[y * dw + x] = (float) s;
            }
        });
        return out;
    }

    private static double[] keys(double t) {
        final double a = -0.75;
        double[] w = new double[4];
        double x0 = 1 + t, x1 = t, x2 = 1 - t, x3 = 2 - t;
        w[0] = ((a * x0 - 5 * a) * x0 + 8 * a) * x0 - 4 * a;
        w[1] = ((a + 2) * x1 - (a + 3)) * x1 * x1 + 1;
        w[2] = ((a + 2) * x2 - (a + 3)) * x2 * x2 + 1;
        w[3] = 1 - w[0] - w[1] - w[2];
        return w;
    }

    public static float[] resizeNearest(float[] src, int sw, int sh, int dw, int dh) {
        float[] out = new float[dw * dh];
        for (int y = 0; y < dh; y++) {
            int sy = Math.min(sh - 1, (int) ((y + 0.5) * sh / dh));
            for (int x = 0; x < dw; x++) {
                int sx = Math.min(sw - 1, (int) ((x + 0.5) * sw / dw));
                out[y * dw + x] = src[sy * sw + sx];
            }
        }
        return out;
    }

    // ---- sampling ----------------------------------------------------

    /** Bilinear sample at pixel coordinates (pixel centres on integers), edge-clamped. */
    public static double bilinear(float[] a, int w, int h, double x, double y) {
        x = clamp(x, 0, w - 1.0001);
        y = clamp(y, 0, h - 1.0001);
        int i = (int) x, j = (int) y;
        double fx = x - i, fy = y - j;
        int k = j * w + i;
        return a[k] * (1 - fx) * (1 - fy) + a[k + 1] * fx * (1 - fy)
                + a[k + w] * (1 - fx) * fy + a[k + w + 1] * fx * fy;
    }

    // ---- distance transform ------------------------------------------

    /**
     * Exact euclidean distance (in pixels) from every pixel to the nearest
     * {@code feature} pixel (Felzenszwalb-Huttenlocher). If {@code nearest} is
     * non-null it receives that feature pixel's index. No features -> +inf.
     */
    public static float[] edt(boolean[] feature, int w, int h, int[] nearest) {
        final float INF = 1e20f;
        float[] g = new float[w * h];          // squared column distance
        int[] gy = new int[w * h];             // row of the nearest feature in the column
        IntStream.range(0, w).parallel().forEach(x -> {
            float[] f = new float[h];
            float[] d = new float[h];
            int[] darg = new int[h];
            for (int y = 0; y < h; y++) {
                f[y] = feature[y * w + x] ? 0 : INF;
            }
            dt1d(f, h, d, darg, new int[h]);
            for (int y = 0; y < h; y++) {
                g[y * w + x] = d[y];
                gy[y * w + x] = darg[y];
            }
        });
        float[] out = new float[w * h];
        IntStream.range(0, h).parallel().forEach(y -> {
            float[] f = new float[w];
            float[] d = new float[w];
            int[] darg = new int[w];
            for (int x = 0; x < w; x++) {
                f[x] = g[y * w + x];
            }
            dt1d(f, w, d, darg, new int[w]);
            for (int x = 0; x < w; x++) {
                int k = y * w + x;
                out[k] = d[x] >= INF * 0.5f ? Float.POSITIVE_INFINITY : (float) Math.sqrt(d[x]);
                if (nearest != null) {
                    int qx = darg[x];
                    nearest[k] = gy[y * w + qx] * w + qx;
                }
            }
        });
        return out;
    }

    /**
     * 1-D squared distance transform of f (lower envelope of parabolas, built
     * from finite samples only); darg = location of the minimising sample.
     */
    private static void dt1d(float[] f, int n, float[] d, int[] darg, int[] v) {
        double[] z = new double[n + 1];
        int k = -1;
        for (int q = 0; q < n; q++) {
            if (f[q] >= 1e19f) continue;
            double s = Double.NEGATIVE_INFINITY;
            while (k >= 0) {
                int p = v[k];
                s = ((f[q] + (double) q * q) - (f[p] + (double) p * p)) / (2.0 * (q - p));
                if (s <= z[k]) {
                    k--;
                } else {
                    break;
                }
            }
            if (k < 0) {
                k = 0;
                v[0] = q;
                z[0] = Double.NEGATIVE_INFINITY;
            } else {
                k++;
                v[k] = q;
                z[k] = s;
            }
            z[k + 1] = Double.POSITIVE_INFINITY;
        }
        if (k < 0) {
            Arrays.fill(d, 0, n, 1e20f);
            Arrays.fill(darg, 0, n, 0);
            return;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) k++;
            int p = v[k];
            d[q] = (float) ((double) (q - p) * (q - p) + f[p]);
            darg[q] = p;
        }
    }

    // ---- morphology --------------------------------------------------

    /** 3x3 opening (erode then dilate) -- removes 1-2 pixel specks. */
    public static boolean[] open3(boolean[] m, int w, int h) {
        return morph(morph(m, w, h, true), w, h, false);
    }

    private static boolean[] morph(boolean[] m, int w, int h, boolean erode) {
        boolean[] out = new boolean[m.length];
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                boolean acc = erode;
                for (int dy = -1; dy <= 1 && acc == erode; dy++) {
                    int yy = Math.min(h - 1, Math.max(0, y + dy));
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = Math.min(w - 1, Math.max(0, x + dx));
                        if (m[yy * w + xx] != erode) {
                            acc = !erode;
                            break;
                        }
                    }
                }
                out[y * w + x] = acc;
            }
        });
        return out;
    }

    /** Mean / std over masked pixels, then {@code a = (a - mean) / std} in place. */
    public static void normalise(float[] a, boolean[] mask) {
        double s = 0, s2 = 0;
        long n = 0;
        for (int i = 0; i < a.length; i++) {
            if (mask == null || mask[i]) {
                s += a[i];
                s2 += (double) a[i] * a[i];
                n++;
            }
        }
        if (n == 0) return;
        double mean = s / n;
        double std = Math.sqrt(Math.max(s2 / n - mean * mean, 0)) + 1e-6;
        for (int i = 0; i < a.length; i++) a[i] = (float) ((a[i] - mean) / std);
    }

    public static float max(float[] a) {
        float m = Float.NEGATIVE_INFINITY;
        for (float v : a) if (v > m) m = v;
        return m;
    }

    public static float[] fill(int n, float v) {
        float[] a = new float[n];
        Arrays.fill(a, v);
        return a;
    }
}
