package com.example.heightmap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline workbench for the terrain post-processing that the FMG generator
 * applies: right now that's just the fractal detail noise (erosion was tried and
 * dropped). Runs against a {@code .fmgh} dump from {@code HeightmapExport} with
 * no Minecraft on the classpath:
 *
 * <pre>
 *   javac -d out src/main/java/com/example/heightmap/HeightField.java \
 *                src/main/java/com/example/heightmap/HeightPostProcess.java
 *   java -cp out com.example.heightmap.HeightPostProcess run/fmg_heightmaps/foo.fmgh --detail
 * </pre>
 *
 * The {@link FractalDetail} pass here is the exact one the generator uses
 * ({@code HeightmapPostConfig.Detail#toPass()}), so previews match in-game.
 */
public final class HeightPostProcess {

    private HeightPostProcess() {
    }

    /** A river polyline in WORLD coords with its Azgaar width/discharge. */
    public static final class River {
        public final int id;
        public final double widthKm;
        public final double sourceWidthKm;
        public final double discharge;
        public final List<int[]> worldPoints = new ArrayList<>();

        public River(int id, double widthKm, double sourceWidthKm, double discharge) {
            this.id = id;
            this.widthKm = widthKm;
            this.sourceWidthKm = sourceWidthKm;
            this.discharge = discharge;
        }

        public void add(int worldX, int worldZ) {
            worldPoints.add(new int[]{worldX, worldZ});
        }
    }

    // ================================================================
    // fractal detail noise
    // ================================================================

    /**
     * Adds multi-octave value-noise detail. Amplitude ramps from ~0 at sea level
     * to full a little above it, so beaches, lake floors and the ocean stay
     * smooth while hills and mountains gain texture.
     */
    public static final class FractalDetail {
        public double amplitude = 6.0;      // blocks, at full strength
        public double baseFrequency = 1.0 / 90.0;
        public int octaves = 5;
        public double lacunarity = 2.0;
        public double gain = 0.5;
        public double fadeStart = 2.0;      // blocks above sea level where detail begins
        public double fadeEnd = 28.0;       // blocks above sea level where detail is full

        /** Noise seed for a given base seed -- so per-block and baked evaluation agree. */
        public static long noiseSeed(long base) {
            return base * 0x9E3779B97F4A7C15L + 0xD1B54A32D192ED03L;
        }

        /** Height offset this pass adds at one world point. */
        public double deltaAt(double worldX, double worldZ, double currentHeight, int seaLevel, long noiseSeed) {
            double n = fbm(worldX, worldZ, noiseSeed);
            double fade = smoothstep(fadeStart, fadeEnd, currentHeight - seaLevel);
            return n * amplitude * fade;
        }

        /** Apply over a whole field (used by the offline CLI). */
        public void apply(HeightField f, long seed) {
            long ns = noiseSeed(seed);
            for (int z = 0; z < f.height; z++) {
                for (int x = 0; x < f.width; x++) {
                    float h = f.get(x, z);
                    f.set(x, z, (float) (h + deltaAt(f.worldX(x), f.worldZ(z), h, f.seaLevel, ns)));
                }
            }
        }

        private double fbm(double x, double z, long ns) {
            double sum = 0, amp = 1, freq = baseFrequency, norm = 0;
            for (int o = 0; o < octaves; o++) {
                sum += amp * (valueNoise(x * freq, z * freq, ns + o * 1013L) * 2.0 - 1.0);
                norm += amp;
                amp *= gain;
                freq *= lacunarity;
            }
            return sum / norm;
        }
    }

    // ================================================================
    // shared math
    // ================================================================

    public static double smoothstep(double edge0, double edge1, double x) {
        if (edge1 <= edge0) return x < edge0 ? 0 : 1;
        double t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
        return t * t * (3 - 2 * t);
    }

    /** Hash-based value noise in [0,1), bilinearly interpolated on the integer lattice. */
    public static double valueNoise(double x, double z, long seed) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double tx = x - x0, tz = z - z0;
        double v00 = hash01(x0, z0, seed);
        double v10 = hash01(x0 + 1, z0, seed);
        double v01 = hash01(x0, z0 + 1, seed);
        double v11 = hash01(x0 + 1, z0 + 1, seed);
        double sx = tx * tx * (3 - 2 * tx);
        double sz = tz * tz * (3 - 2 * tz);
        double a = v00 + (v10 - v00) * sx;
        double b = v01 + (v11 - v01) * sx;
        return a + (b - a) * sz;
    }

    static double hash01(int x, int z, long seed) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        h ^= (h >>> 29);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 32);
        return (h >>> 11) * (1.0 / (1L << 53));
    }

    // ================================================================
    // CLI
    // ================================================================

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("usage: HeightPostProcess <in.fmgh> [--seed N] [--detail[=amp]] [--out <base>]");
            System.out.println("  (no flags => --detail)");
            return;
        }
        Path in = Path.of(args[0]);
        long seed = 1;
        Path outBase = null;
        FractalDetail detail = null;

        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            String val = null;
            int eq = a.indexOf('=');
            if (eq >= 0) { val = a.substring(eq + 1); a = a.substring(0, eq); }
            switch (a) {
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--out" -> outBase = Path.of(args[++i]);
                case "--detail" -> {
                    detail = new FractalDetail();
                    if (val != null) detail.amplitude = Double.parseDouble(val);
                }
                default -> System.out.println("(ignoring unknown option " + args[i] + ")");
            }
        }
        if (detail == null) detail = new FractalDetail();
        if (outBase == null) {
            String n = in.getFileName().toString().replaceFirst("\\.fmgh$", "") + ".out";
            outBase = in.resolveSibling(n);
        }

        HeightField field = HeightField.read(in);
        HeightField before = field.copy();
        System.out.printf(Locale.ROOT, "loaded %dx%d  origin (%d,%d)  sea %d  range [%.1f, %.1f]%n",
                field.width, field.height, field.originX, field.originZ, field.seaLevel,
                field.min(), field.max());

        long t0 = System.nanoTime();
        detail.apply(field, seed);
        System.out.printf(Locale.ROOT, "  detail  %.1f s   range now [%.1f, %.1f]%n",
                (System.nanoTime() - t0) / 1e9, field.min(), field.max());

        Path fmgh = Path.of(outBase + ".fmgh");
        field.write(fmgh);
        float[] range = field.writeHeightPng(Path.of(outBase + ".height.png"));
        field.writeHillshadePng(Path.of(outBase + ".hillshade.png"));
        double maxChange = field.writeDiffPng(before, Path.of(outBase + ".diff.png"));
        System.out.printf(Locale.ROOT,
                "wrote %s%n  .height.png  %.1f..%.1f   .hillshade.png   .diff.png (full colour = %.2f blocks)%n",
                fmgh, range[0], range[1], maxChange);
    }
}
