package com.example.terrain;

import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * Stream-power hydraulic erosion on a raster, as in refinemap/refine.py:
 * priority-flood depression filling (which also yields a drainage order),
 * slope-weighted random steepest-descent receivers, an implicit stream-power
 * update, and a thermal (talus) relaxation.
 */
public final class Erosion {

    static final int[] DX8 = {1, 1, 0, -1, -1, -1, 0, 1};
    static final int[] DY8 = {0, 1, 1, 1, 0, -1, -1, -1};
    static final float[] DL8 = {1f, 1.4142135f, 1f, 1.4142135f, 1f, 1.4142135f, 1f, 1.4142135f};

    /** Drainage graph from the last routing pass; buffers are reused between passes. */
    public static final class Flow {
        public final double[] filled;
        public final int[] receiver;
        public final float[] receiverDist;   // in cells
        public final int[] order;            // outlets first, then by filled height
        public final double[] area;          // upstream area, blocks^2
        private final boolean[] done;
        private final double[] heapKey;
        private final int[] heapIdx;

        public Flow(int n) {
            filled = new double[n];
            receiver = new int[n];
            receiverDist = new float[n];
            order = new int[n];
            area = new double[n];
            done = new boolean[n];
            heapKey = new double[n];
            heapIdx = new int[n];
        }
    }

    private Erosion() {
    }

    /**
     * Priority-flood fill with an epsilon gradient. Every cell gets a receiver
     * (the cell it was reached from) and a position in {@code order}, which is
     * sorted by filled height -- so receivers always come first.
     */
    public static void floodRoute(double[] z, boolean[] outlet, int w, int h, Flow f) {
        int n = w * h;
        double[] zf = f.filled;
        System.arraycopy(z, 0, zf, 0, n);
        int[] rec = f.receiver;
        float[] rdist = f.receiverDist;
        int[] order = f.order;
        boolean[] done = f.done;
        java.util.Arrays.fill(done, false);
        double[] hk = f.heapKey;
        int[] hi = f.heapIdx;
        int size = 0;
        for (int k = 0; k < n; k++) {
            rec[k] = k;
            rdist[k] = 1f;
            int y = k / w, x = k - y * w;
            if (outlet[k] || x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                done[k] = true;
                size = push(hk, hi, size, zf[k], k);
            }
        }
        int cnt = 0;
        while (size > 0) {
            int c = hi[0];
            size = pop(hk, hi, size);
            order[cnt++] = c;
            int cy = c / w, cx = c - cy * w;
            for (int d = 0; d < 8; d++) {
                int nx = cx + DX8[d], ny = cy + DY8[d];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int nb = ny * w + nx;
                if (done[nb]) continue;
                done[nb] = true;
                if (zf[nb] <= zf[c]) zf[nb] = zf[c] + 1e-4;
                rec[nb] = c;
                rdist[nb] = DL8[d];
                size = push(hk, hi, size, zf[nb], nb);
            }
        }
    }

    private static int push(double[] hk, int[] hi, int n, double key, int idx) {
        int k = n;
        hk[k] = key;
        hi[k] = idx;
        while (k > 0) {
            int p = (k - 1) >> 1;
            if (hk[p] <= hk[k]) break;
            double tk = hk[p];
            hk[p] = hk[k];
            hk[k] = tk;
            int ti = hi[p];
            hi[p] = hi[k];
            hi[k] = ti;
            k = p;
        }
        return n + 1;
    }

    private static int pop(double[] hk, int[] hi, int n) {
        n--;
        hk[0] = hk[n];
        hi[0] = hi[n];
        int k = 0;
        while (true) {
            int l = 2 * k + 1;
            if (l >= n) break;
            int r = l + 1;
            int c = (r < n && hk[r] < hk[l]) ? r : l;
            if (hk[k] <= hk[c]) break;
            double tk = hk[c];
            hk[c] = hk[k];
            hk[k] = tk;
            int ti = hi[c];
            hi[c] = hi[k];
            hi[k] = ti;
            k = c;
        }
        return n;
    }

    /**
     * Replace flood receivers with a downhill neighbour picked at random,
     * weighted by slope squared: mostly steepest descent, but without the
     * axis-aligned "combing" of plain D8.
     */
    public static void steepestReceivers(Flow f, boolean[] outlet, int w, int h, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] z = f.filled;
        double[] sl = new double[8];
        for (int k = 0; k < w * h; k++) {
            if (outlet[k]) {
                f.receiver[k] = k;
                continue;
            }
            int y = k / w, x = k - y * w;
            double tot = 0;
            for (int d = 0; d < 8; d++) {
                sl[d] = 0;
                int nx = x + DX8[d], ny = y + DY8[d];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                double s = (z[k] - z[ny * w + nx]) / DL8[d];
                if (s > 0) {
                    sl[d] = s * s;
                    tot += sl[d];
                }
            }
            if (tot <= 0) continue;
            double u = rnd.nextDouble() * tot;
            for (int d = 0; d < 8; d++) {
                u -= sl[d];
                if (u <= 0 && sl[d] > 0) {
                    f.receiver[k] = (y + DY8[d]) * w + x + DX8[d];
                    f.receiverDist[k] = DL8[d];
                    break;
                }
            }
        }
    }

    /**
     * One implicit stream-power step, applied in place to {@code f.filled}
     * (returned); the upstream area ends up in {@code f.area}.
     */
    public static double[] streamPower(Flow f, double areaCell, double k, double dx, double acrit) {
        int n = f.filled.length;
        double[] z = f.filled;
        double[] a = f.area;
        java.util.Arrays.fill(a, areaCell);
        int[] order = f.order;
        int[] rec = f.receiver;
        for (int t = n - 1; t >= 0; t--) {           // upstream -> downstream
            int c = order[t];
            int r = rec[c];
            if (r != c) a[r] += a[c];
        }
        for (int t = 0; t < n; t++) {                // receivers first
            int c = order[t];
            int r = rec[c];
            if (r == c) continue;
            double ac = a[c];
            double sat = ac / (ac + acrit);
            double ff = k * Math.sqrt(ac) * sat * sat / (f.receiverDist[c] * dx);
            z[c] = (z[c] + ff * z[r]) / (1 + ff);
        }
        return z;
    }

    /** Talus relaxation: slopes above {@code talus} (height per cell) slump toward it. */
    public static double[] thermal(double[] z, int w, int h, double talus, double rate, boolean[] fixed) {
        double[] out = z.clone();
        IntStream.range(1, h - 1).parallel().forEach(y -> {
            for (int x = 1; x < w - 1; x++) {
                int k = y * w + x;
                if (fixed[k]) continue;
                double c = z[k];
                double delta = 0;
                for (int d = 0; d < 8; d++) {
                    double nb = z[(y + DY8[d]) * w + x + DX8[d]];
                    double lim = talus * DL8[d];
                    double diff = c - nb;
                    if (diff > lim) delta -= (diff - lim) * rate / 8;
                    else if (-diff > lim) delta += (-diff - lim) * rate / 8;
                }
                out[k] = c + delta;
            }
        });
        return out;
    }

    /**
     * Carry an erosion weight down the drainage network (channels keep it for
     * ~lChan blocks), then back up onto valley sides (~lSide blocks). This is
     * what lets a mountain valley fade out gradually on its way to the plain
     * instead of stopping dead where the altitude weight reaches zero.
     */
    public static float[] valleyWeight(float[] w0, Flow f, double dx, double aChan, double lChan, double lSide) {
        int n = w0.length;
        float[] w = w0.clone();
        for (int t = n - 1; t >= 0; t--) {
            int k = f.order[t];
            int r = f.receiver[k];
            if (r == k || f.area[k] < aChan) continue;
            float v = (float) (w[k] * Math.exp(-f.receiverDist[k] * dx / lChan));
            if (v > w[r]) w[r] = v;
        }
        for (int t = 0; t < n; t++) {
            int k = f.order[t];
            int r = f.receiver[k];
            if (r == k) continue;
            float v = (float) (w[r] * Math.exp(-f.receiverDist[k] * dx / lSide));
            if (v > w[k]) w[k] = v;
        }
        return w;
    }
}
