package com.example.fmg;

import java.util.*;
import com.example.util.CatmullRomSpline;
import com.example.util.CatmullRomSpline.Vec2;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;

public final class FMRiverSampler {

    private static final double SAMPLE_STEP = 5.0;
    private static final double BANK_WIDTH = 4.0;
    private static final double BASE_DEPTH = 6.0;
    private static final double SEA_LEVEL = 63.0;

    /** One sampled river point */

    
    public static final class RiverSample {
        public final double x, z;
        public final double halfWidth;

        RiverSample(double x, double z, double halfWidth) {
            this.x = x;
            this.z = z;
            this.halfWidth = halfWidth;
        }
    }

    /** Query result */
    public static final class RiverQuery {
        public final boolean hit;
        public final double distance;
        public final RiverSample sample;

        RiverQuery(boolean hit, double distance, RiverSample sample) {
            this.hit = hit;
            this.distance = distance;
            this.sample = sample;
        }
    }

    // Indexed by chunk
    private static final Map<ChunkPos, List<RiverSample>> samplesByChunk = new HashMap<>();

    private FMRiverSampler() {}

    /* ------------------------------------------------------------ */

    /** Call ONCE after loading FMG data */
    public static void build(FMGMapData data) {
        samplesByChunk.clear();

        for (FMGRiver river : data.getRivers()) {
            if (river.getPoints() == null || river.getPoints().size() < 2)
                continue;

            List<Vec2> control = new ArrayList<>();
            for (double[] p : river.getPoints()) {
                control.add(new Vec2(p[0], p[1]));
            }

            List<Vec2> sampled = CatmullRomSpline.sample(control, SAMPLE_STEP);

            for (int i = 0; i < sampled.size(); i++) {
                Vec2 p = sampled.get(i);
                double t = i / (double)Math.max(1, sampled.size() - 1);

                double width =
                    river.getSourceWidth() +
                    t * (river.getWidth() - river.getSourceWidth());

                RiverSample s = new RiverSample(p.x(), p.z(), width * 0.5);

                ChunkPos cp = new ChunkPos(
                    
                    MathHelper.floor(p.x()) >> 4,
                    MathHelper.floor(p.z()) >> 4
                );

                samplesByChunk
                    .computeIfAbsent(cp, k -> new ArrayList<>())
                    .add(s);
            }
        }
    }

    /* ------------------------------------------------------------ */

    public static RiverQuery query(int x, int z) {
        ChunkPos cp = new ChunkPos(x >> 4, z >> 4);

        double best = Double.MAX_VALUE;
        RiverSample bestSample = null;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ChunkPos n = new ChunkPos(cp.x + dx, cp.z + dz);
                List<RiverSample> list = samplesByChunk.get(n);
                if (list == null) continue;

                for (RiverSample s : list) {
                    double d = dist(x, z, s.x, s.z);
                    if (d < best) {
                        best = d;
                        bestSample = s;
                    }
                }
            }
        }

        if (bestSample == null) {
            return new RiverQuery(false, 0, null);
        }

        double max = bestSample.halfWidth + BANK_WIDTH;
        if (best > max) {
            return new RiverQuery(false, best, bestSample);
        }

        return new RiverQuery(true, best, bestSample);
    }

    /* ------------------------------------------------------------ */

    /**
     * Returns the carved river bed height for this query using the default
     * internal sea level (63). The bed is deepest at the center of the
     * river and smoothly rises towards the banks.
     */
    public static double carvedHeight(RiverQuery q) {
        return carvedHeight(q, SEA_LEVEL);
    }

    /**
     * Returns the carved river bed height for this query, using the
     * provided {@code seaLevel}. Depth scales with the local river
     * width so large rivers cut a deeper channel than small ones.
     */
    public static double carvedHeight(RiverQuery q, double seaLevel) {
        double d = q.distance;
        double r = q.sample.halfWidth;

        // Scale maximum depth by river width. Narrow streams are shallower,
        // wide rivers get a deeper channel. The width factor is clamped so
        // extremely wide values from the FMG export do not produce
        // ridiculous depths.
        double widthFactor = r / 4.0; // half‑width 4 -> factor 1
        if (widthFactor < 0.5) widthFactor = 0.5;   // minimum 0.5x depth
        if (widthFactor > 2.0) widthFactor = 2.0;   // maximum 2x depth

        double maxDepth = BASE_DEPTH * widthFactor;

        double t;
        if (d <= r) {
            // Inside the wet channel: full depth.
            t = 1.0;
        } else {
            // Between channel edge and outer bank we smoothly fade the
            // depth back to the terrain using a smoothstep profile.
            double u = (d - r) / BANK_WIDTH;
            u = MathHelper.clamp(u, 0.0, 1.0);
            t = 1.0 - smoothstep(u);
        }

        return seaLevel - (t * maxDepth);
    }

    private static double smoothstep(double x) {
        return x * x * (3 - 2 * x);
    }

    private static double dist(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return Math.sqrt(dx*dx + dz*dz);
    }
}
