package com.example.heightmap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Generates a realistic {@code .fmgh} + {@code .rivers} test fixture without
 * needing the game: an fBm continent with a coastline, plus a river network
 * extracted from D8 flow accumulation. Use it to iterate on
 * {@link HeightPostProcess} passes before you have a real {@code /fmg heightmap}
 * export to hand.
 *
 * <pre>
 *   javac -d out src/main/java/com/example/heightmap/HeightField.java \
 *                src/main/java/com/example/heightmap/SyntheticSample.java
 *   java -cp out com.example.heightmap.SyntheticSample sample 700 12345
 * </pre>
 */
public final class SyntheticSample {

    private SyntheticSample() {
    }

    public static void main(String[] args) throws IOException {
        String base = args.length > 0 ? args[0] : "sample";
        int radius = args.length > 1 ? Integer.parseInt(args[1]) : 700;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 20260829L;

        int sea = 63, minY = -64, maxY = 320;
        int size = 2 * radius + 1;
        HeightField f = new HeightField(-radius, -radius, size, size, sea, minY, maxY);

        // --- base height: domain-warped fBm, radial falloff to a coastline ---
        double warpFreq = 1.0 / 700.0, warpAmp = 240.0;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                double wx = f.worldX(x), wz = f.worldZ(z);
                double wxo = wx + warpAmp * (noise(wx * warpFreq, wz * warpFreq, seed + 11) * 2 - 1);
                double wzo = wz + warpAmp * (noise(wx * warpFreq, wz * warpFreq, seed + 23) * 2 - 1);

                double cont = fbm(wxo, wzo, 1.0 / 1600.0, 6, seed);          // continent shape
                double hills = fbm(wxo, wzo, 1.0 / 260.0, 5, seed + 7);       // hills
                double ridged = ridge(wxo, wzo, 1.0 / 520.0, 5, seed + 31);   // mountain ridges

                double rr = Math.hypot(wx, wz) / (radius * 0.95);
                double falloff = 1.0 - smooth01((rr - 0.55) / 0.5);           // 1 inland -> 0 past the rim

                double land = cont * 1.05 + hills * 0.35 + Math.max(0, ridged - 0.35) * 1.4;
                double h = sea - 26 + falloff * (52 + land * 150);
                f.set(x, z, (float) h);
            }
        }
        smooth(f, 1);

        // --- river network from D8 flow accumulation ---
        List<Poly> rivers = extractRivers(f);

        Path dir = Path.of(base).toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path fmgh = Path.of(base + ".fmgh");
        f.write(fmgh);
        f.writeHeightPng(Path.of(base + ".height.png"));
        f.writeHillshadePng(Path.of(base + ".hillshade.png"));
        writeRivers(Path.of(base + ".rivers"), f, rivers);

        System.out.printf(Locale.ROOT,
                "wrote %s  %dx%d  origin (%d,%d)  range [%.1f, %.1f]  %d rivers%n",
                fmgh, size, size, f.originX, f.originZ, f.min(), f.max(), rivers.size());
    }

    // ---- river extraction ----------------------------------------

    private record Poly(int id, double widthKm, double sourceWidthKm, double discharge, List<int[]> world) {
    }

    private static final int[][] D8 = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private static List<Poly> extractRivers(HeightField f) {
        int w = f.width, h = f.height, n = w * h;

        // steepest-descent receiver per cell (-1 = pit/edge/sea)
        int[] recv = new int[n];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
            int x = i % w, z = i / w;
            float hc = f.data[i];
            int best = -1;
            double bestDrop = 0;
            for (int[] d : D8) {
                int nx = x + d[0], nz = z + d[1];
                if (nx < 0 || nx >= w || nz < 0 || nz >= h) continue;
                double drop = (hc - f.get(nx, nz)) / Math.hypot(d[0], d[1]);
                if (drop > bestDrop) { bestDrop = drop; best = nz * w + nx; }
            }
            recv[i] = best;
        }
        Arrays.sort(order, (a, b) -> Float.compare(f.data[b], f.data[a])); // high -> low

        double[] acc = new double[n];
        Arrays.fill(acc, 1.0);
        for (int idx : order) {
            int r = recv[idx];
            if (r >= 0) acc[r] += acc[idx];
        }

        double threshold = n * 0.0016; // ~ how much upstream area makes a channel
        List<Poly> rivers = new ArrayList<>();
        boolean[] used = new boolean[n];
        int id = 1;

        for (int idx : order) {
            if (acc[idx] < threshold || used[idx] || f.data[idx] < f.seaLevel) continue;
            // a head: no upstream neighbour that also exceeds the threshold
            boolean isHead = true;
            int hx = idx % w, hz = idx / w;
            for (int[] d : D8) {
                int nx = hx + d[0], nz = hz + d[1];
                if (nx < 0 || nx >= w || nz < 0 || nz >= h) continue;
                int ni = nz * w + nx;
                if (recv[ni] == idx && acc[ni] >= threshold) { isHead = false; break; }
            }
            if (!isHead) continue;

            List<int[]> pts = new ArrayList<>();
            int cur = idx;
            double maxAcc = acc[idx];
            int guard = 0;
            while (cur >= 0 && guard++ < n) {
                used[cur] = true;
                pts.add(new int[]{f.worldX(cur % w), f.worldZ(cur / w)});
                maxAcc = Math.max(maxAcc, acc[cur]);
                if (f.data[cur] < f.seaLevel) break;
                cur = recv[cur];
            }
            if (pts.size() < 6) continue;

            double discharge = maxAcc;
            double widthKm = 0.05 + 0.5 * Math.sqrt(maxAcc / threshold) / 8.0;
            rivers.add(new Poly(id++, widthKm, 0.04, discharge, pts));
        }
        return rivers;
    }

    private static void writeRivers(Path file, HeightField f, List<Poly> rivers) throws IOException {
        try (var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("# fmg-heightmap-rivers " + f.originX + " " + f.originZ + " " + f.width + " " + f.height);
            out.newLine();
            for (Poly r : rivers) {
                out.write(String.format(Locale.ROOT,
                        "river %d width=%.3f sourceWidth=%.3f discharge=%.3f points=%d",
                        r.id, r.widthKm, r.sourceWidthKm, r.discharge, r.world.size()));
                out.newLine();
                for (int[] p : r.world) {
                    out.write(p[0] + " " + p[1]);
                    out.newLine();
                }
            }
        }
    }

    // ---- noise helpers (self-contained) --------------------------

    private static void smooth(HeightField f, int passes) {
        for (int p = 0; p < passes; p++) {
            float[] c = f.data.clone();
            for (int z = 0; z < f.height; z++) {
                for (int x = 0; x < f.width; x++) {
                    float s = 0; int k = 0;
                    for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, nz = z + dz;
                        if (nx < 0 || nx >= f.width || nz < 0 || nz >= f.height) continue;
                        s += c[nz * f.width + nx]; k++;
                    }
                    f.set(x, z, s / k);
                }
            }
        }
    }

    private static double fbm(double x, double z, double freq, int octaves, long seed) {
        double sum = 0, amp = 1, norm = 0;
        for (int o = 0; o < octaves; o++) {
            sum += amp * (noise(x * freq, z * freq, seed + o * 1013L) * 2 - 1);
            norm += amp; amp *= 0.5; freq *= 2.0;
        }
        return sum / norm;
    }

    private static double ridge(double x, double z, double freq, int octaves, long seed) {
        double sum = 0, amp = 1, norm = 0;
        for (int o = 0; o < octaves; o++) {
            double v = 1.0 - Math.abs(noise(x * freq, z * freq, seed + o * 977L) * 2 - 1);
            sum += amp * v * v;
            norm += amp; amp *= 0.5; freq *= 2.0;
        }
        return sum / norm;
    }

    private static double noise(double x, double z, long seed) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double tx = x - x0, tz = z - z0;
        double v00 = h01(x0, z0, seed), v10 = h01(x0 + 1, z0, seed);
        double v01 = h01(x0, z0 + 1, seed), v11 = h01(x0 + 1, z0 + 1, seed);
        double sx = tx * tx * (3 - 2 * tx), sz = tz * tz * (3 - 2 * tz);
        double a = v00 + (v10 - v00) * sx, b = v01 + (v11 - v01) * sx;
        return a + (b - a) * sz;
    }

    private static double h01(int x, int z, long seed) {
        long v = seed;
        v ^= x * 0x9E3779B97F4A7C15L;
        v ^= z * 0xC2B2AE3D27D4EB4FL;
        v ^= (v >>> 29); v *= 0xBF58476D1CE4E5B9L; v ^= (v >>> 32);
        return (v >>> 11) * (1.0 / (1L << 53));
    }

    private static double smooth01(double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t * t * (3 - 2 * t);
    }
}
