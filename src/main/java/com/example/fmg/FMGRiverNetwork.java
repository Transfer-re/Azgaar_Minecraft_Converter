package com.example.fmg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.heightmap.HeightPostProcess;
import com.example.heightmap.HeightmapPostConfig;
import com.example.util.CatmullRomSpline;

import net.minecraft.util.math.ChunkPos;

/**
 * The FMG river system as carved watercourses -- the river analogue of
 * {@link FMGRouteSampler}. Built once per FMG export and cached.
 *
 * <p>Each Azgaar river keeps its endpoints (mouth and source stay exactly where
 * the map put them) but everything between is rebuilt so the result reads as a
 * real watercourse:
 * <ol>
 *   <li><b>Route by descent.</b> The smoothed cell-centre spline is snapped
 *       sideways onto the valley floor (a perpendicular search for the lowest
 *       ground, then the offsets are smoothed), so the river follows valleys
 *       instead of cutting across whatever the spline happened to hit. A slope-
 *       faded meander adds wander on flat ground.</li>
 *   <li><b>Bed profile.</b> The terrain along the path is turned into a running
 *       minimum walking downstream, then smoothed and re-clamped under the
 *       terrain. The bed therefore never runs uphill and never copies every
 *       bump -- and because it is always at or below the terrain, carving only
 *       ever lowers ground.</li>
 *   <li><b>Width and depth follow flow and slope.</b> Steep stretches narrow and
 *       shallow into rapids; flat stretches stay wide and deep. Flat reaches
 *       (the ones joining oceans and lakes) keep the river's full width and a
 *       boat-friendly depth, so a wide channel stays navigable end to end.</li>
 *   <li><b>Water surface.</b> Minecraft water is flat, so the continuous bed is
 *       quantised into flat pools: 1-block drops (rapids, jittered so they are
 *       not evenly spaced) until the bed slope passes a threshold, where drops
 *       merge into real waterfalls. Pool level is frozen across road crossings
 *       so bridges always span flat water.</li>
 * </ol>
 *
 * <p>The cross-section itself is carved by {@link ImageMapChunkGenerator} from
 * {@link #valleyFloorY}: a curved channel bed inside the banks, then a constant-
 * slope V-valley outside it, blended into the surrounding terrain with
 * {@link #smoothMin} so the valley rim is not a crease. The valley widens by
 * itself where the river sits deep below a hillside and stays narrow where it
 * does not.
 */
public final class FMGRiverNetwork {

    private static final Logger LOGGER = LoggerFactory.getLogger("FMGRiverNetwork");
    private static final Map<String, FMGRiverNetwork> CACHE = new ConcurrentHashMap<>();

    private static final double SAMPLE_STEP = 2.0;

    /** One point along a carved river. */
    public static final class RiverSample {
        public final double x;
        public final double z;
        public final double halfWidth;
        public final int waterSurfaceY;
        public final int centerDepth;   // water depth at the channel centre
        public final double bankSlope;  // blocks of rise per block out from the channel edge

        RiverSample(double x, double z, double halfWidth, int waterSurfaceY, int centerDepth, double bankSlope) {
            this.x = x;
            this.z = z;
            this.halfWidth = halfWidth;
            this.waterSurfaceY = waterSurfaceY;
            this.centerDepth = centerDepth;
            this.bankSlope = bankSlope;
        }
    }

    /** Nearest-river result for a world column. */
    public static final class Hit {
        public final RiverSample sample;
        public final double distance;

        Hit(RiverSample sample, double distance) {
            this.sample = sample;
            this.distance = distance;
        }

        /** Normalised distance from the centreline, 0 (centre) .. 1 (channel edge). */
        public double u() {
            return sample.halfWidth <= 0 ? 1.0 : Math.min(1.0, distance / sample.halfWidth);
        }
    }

    private final Map<Long, List<RiverSample>> byChunk = new HashMap<>();
    private final double bankWidth;
    private final double maxValleyRadius;
    private final double valleySmoothK;
    /** Widest reach any sample can carve to -- how far the chunk search has to look. */
    private double maxInfluence;

    private FMGRiverNetwork(double bankWidth, double maxValleyRadius, double valleySmoothK) {
        this.bankWidth = bankWidth;
        this.maxValleyRadius = maxValleyRadius;
        this.valleySmoothK = valleySmoothK;
    }

    public double maxValleyRadius() {
        return maxValleyRadius;
    }

    public double valleySmoothK() {
        return valleySmoothK;
    }

    // ---- build / cache -----------------------------------------

    public static FMGRiverNetwork get(String fmgExport, FMGMapData map, int minY, int maxY, int seaLevel) {
        FMGRiverNetwork n = CACHE.get(fmgExport);
        if (n != null) return n;
        synchronized (CACHE) {
            n = CACHE.get(fmgExport);
            if (n != null) return n;
            n = build(fmgExport, map, minY, maxY, seaLevel);
            CACHE.put(fmgExport, n);
            return n;
        }
    }

    public static void invalidate() {
        CACHE.clear();
    }

    private static FMGRiverNetwork build(String fmgExport, FMGMapData map, int minY, int maxY, int seaLevel) {
        long t0 = System.currentTimeMillis();
        HeightmapPostConfig.Rivers cfg = HeightmapPostConfig.get().rivers;
        FMGRiverNetwork net = new FMGRiverNetwork(cfg.bankWidth, cfg.maxValleyRadius, cfg.valleySmoothK);

        if (!HeightmapPostConfig.get().enabled || !cfg.enabled
                || map == null || map.getInfo() == null || map.getRivers() == null) {
            return net;
        }

        double scale = FMGHeightSampler.sampleScale();
        double offX = -(map.getInfo().getWidth() * scale) / 2.0;
        double offZ = -(map.getInfo().getHeight() * scale) / 2.0;

        double maxDischarge = 1.0;
        for (FMGRiver r : map.getRivers()) maxDischarge = Math.max(maxDischarge, r.getDischarge());

        // shared chunk-bucketed route index for road-crossing detection
        FMGRouteIndex routeIndex = FMGRouteIndex.get(fmgExport, map);
        long noiseSeed = HeightmapPostConfig.get().seed != 0
                ? HeightmapPostConfig.get().seed
                : (fmgExport == null ? 1L : fmgExport.hashCode() * 0x9E3779B97F4A7C15L);

        int rivers = 0, samples = 0;
        for (FMGRiver river : map.getRivers()) {
            List<CatmullRomSpline.Vec2> control = new ArrayList<>();
            if (river.getCells() != null) {
                for (int cellId : river.getCells()) {
                    FMGCell c = map.getCell(cellId);
                    if (c == null || c.getP() == null || c.getP().length < 2) continue;
                    control.add(new CatmullRomSpline.Vec2(c.getPx() * scale + offX, c.getPy() * scale + offZ));
                }
            }
            if (control.size() < 2) continue;

            List<CatmullRomSpline.Vec2> spline = CatmullRomSpline.sample(control, SAMPLE_STEP);
            if (spline.size() < 4) continue;

            int n = spline.size();
            double[] px = new double[n];
            double[] pz = new double[n];
            for (int i = 0; i < n; i++) {
                px[i] = spline.get(i).x();
                pz[i] = spline.get(i).z();
            }

            // orient mouth -> source (index 0 is the low end, so "upstream" is +i)
            if (terrainAt(map, px[0], pz[0], minY, maxY, seaLevel)
                    > terrainAt(map, px[n - 1], pz[n - 1], minY, maxY, seaLevel)) {
                reverse(px);
                reverse(pz);
            }

            long riverSeed = noiseSeed ^ (river.getI() * 0x9E3779B97F4A7C15L);

            // ---- 1. route: slide onto the valley floor, then meander ----
            snapToValleyFloor(map, px, pz, cfg, minY, maxY, seaLevel);
            double[] terrain = terrainProfile(map, px, pz, minY, maxY, seaLevel);
            meander(px, pz, terrain, cfg, riverSeed);
            terrain = terrainProfile(map, px, pz, minY, maxY, seaLevel);

            // ---- 2. surface profile: monotonic downstream, then smoothed ----
            double[] surf = surfaceProfile(terrain, seaLevel, cfg);
            double[] slope = slopeProfile(surf);

            // ---- 3. width + depth from flow and slope ----
            double dNorm = river.getDischarge() / maxDischarge;
            double[] halfW = widthProfile(river, dNorm, slope, cfg);
            double[] depth = depthProfile(dNorm, halfW, slope, cfg);

            // ---- 4. flat pools with rapids / waterfalls between them ----
            boolean[] frozen = roadCrossingWindows(routeIndex, px, pz, halfW, cfg);
            int[] water = waterProfile(surf, frozen, seaLevel, maxY, cfg, riverSeed);

            // ---- 5. emit ----
            for (int i = 0; i < n; i++) {
                int centerDepth = Math.max(1, (int) Math.round(depth[i]));
                double bankSlope = bankSlopeAt(map, px, pz, i, halfW[i], water[i], cfg, minY, maxY, seaLevel);
                net.add(new RiverSample(px[i], pz[i], halfW[i], water[i], centerDepth, bankSlope));
                samples++;
            }
            rivers++;
        }

        net.maxInfluence = 0.0;
        for (List<RiverSample> bucket : net.byChunk.values()) {
            for (RiverSample s : bucket) {
                net.maxInfluence = Math.max(net.maxInfluence, s.halfWidth);
            }
        }
        net.maxInfluence += cfg.maxValleyRadius + cfg.valleySmoothK + 1.0;

        LOGGER.info("FMGRiverNetwork build: {} rivers, {} samples, {} ms",
                rivers, samples, System.currentTimeMillis() - t0);
        return net;
    }

    // ---- build helpers -----------------------------------------

    private static int terrainAt(FMGMapData map, double x, double z, int minY, int maxY, int seaLevel) {
        return FMGHeightSampler.sampleHeight(map, (int) Math.round(x), (int) Math.round(z), minY, maxY, seaLevel);
    }

    private static double[] terrainProfile(FMGMapData map, double[] px, double[] pz,
                                           int minY, int maxY, int seaLevel) {
        double[] out = new double[px.length];
        for (int i = 0; i < px.length; i++) {
            out[i] = terrainAt(map, px[i], pz[i], minY, maxY, seaLevel);
        }
        return out;
    }

    /**
     * Slides each sample sideways onto the lowest ground within
     * {@code valleySnapRadius}, so the river ends up on the valley floor rather
     * than wherever the spline happened to run. Offsets are smoothed (the path
     * stays smooth) and tapered to zero at both ends, so the mouth and source
     * stay exactly where Azgaar put them.
     */
    private static void snapToValleyFloor(FMGMapData map, double[] px, double[] pz,
                                          HeightmapPostConfig.Rivers cfg, int minY, int maxY, int seaLevel) {
        int n = px.length;
        if (cfg.valleySnapRadius <= 0 || n < 8) return;
        double step = Math.max(1.0, cfg.valleySnapStep);

        double[] off = new double[n];
        for (int i = 1; i < n - 1; i++) {
            double dirX = px[i + 1] - px[i - 1];
            double dirZ = pz[i + 1] - pz[i - 1];
            double dl = Math.hypot(dirX, dirZ);
            if (dl < 1e-6) continue;
            double perpX = -dirZ / dl;
            double perpZ = dirX / dl;

            double bestScore = Double.MAX_VALUE;
            double bestOff = 0.0;
            for (double o = -cfg.valleySnapRadius; o <= cfg.valleySnapRadius; o += step) {
                double h = terrainAt(map, px[i] + perpX * o, pz[i] + perpZ * o, minY, maxY, seaLevel);
                double score = h + cfg.valleySnapBias * Math.abs(o);
                if (score < bestScore) {
                    bestScore = score;
                    bestOff = o;
                }
            }
            off[i] = bestOff;
        }

        smooth(off, cfg.valleySnapSmoothing, 2);
        taperEnds(off);

        for (int i = 1; i < n - 1; i++) {
            if (off[i] == 0.0) continue;
            double dirX = px[i + 1] - px[i - 1];
            double dirZ = pz[i + 1] - pz[i - 1];
            double dl = Math.hypot(dirX, dirZ);
            if (dl < 1e-6) continue;
            px[i] += (-dirZ / dl) * off[i];
            pz[i] += (dirX / dl) * off[i];
        }
    }

    /** Perpendicular wander, full strength on flat ground and fading out on slopes. */
    private static void meander(double[] px, double[] pz, double[] terrain,
                                HeightmapPostConfig.Rivers cfg, long seed) {
        int n = px.length;
        if (cfg.meanderAmplitude <= 0 || n < 8) return;
        double wavelength = Math.max(8.0, cfg.meanderWavelength);

        double[] off = new double[n];
        for (int i = 1; i < n - 1; i++) {
            double localSlope = Math.abs(terrain[i + 1] - terrain[i - 1]) / (2.0 * SAMPLE_STEP);
            double flat = clamp(1.0 - localSlope * 6.0, 0.0, 1.0);
            double t = i * SAMPLE_STEP / wavelength;
            double noise = HeightPostProcess.valueNoise(t, 0.0, seed) * 2.0 - 1.0;
            off[i] = noise * cfg.meanderAmplitude * flat;
        }
        smooth(off, 3, 1);
        taperEnds(off);

        for (int i = 1; i < n - 1; i++) {
            double dirX = px[i + 1] - px[i - 1];
            double dirZ = pz[i + 1] - pz[i - 1];
            double dl = Math.hypot(dirX, dirZ);
            if (dl < 1e-6) continue;
            px[i] += (-dirZ / dl) * off[i];
            pz[i] += (dirX / dl) * off[i];
        }
    }

    /**
     * The water-surface profile: the running minimum of the terrain walking
     * downstream, then smoothed and re-clamped under the terrain. It never rises
     * downstream (so the river cannot flow uphill) and never sits above the
     * ground (so the river is incised into the land rather than running along
     * the top of it). The channel is then dug {@code depth} below this, which is
     * why carving only ever lowers ground.
     */
    private static double[] surfaceProfile(double[] terrain, int seaLevel, HeightmapPostConfig.Rivers cfg) {
        int n = terrain.length;
        double[] surf = terrain.clone();
        // index 0 is the mouth, so "downstream" is decreasing i
        runningMinDownstream(surf);
        // a river that reaches the sea meets it flush; one that ends in an inland
        // lake keeps that lake's level instead of being dragged down to sea level
        if (terrain[0] <= seaLevel + 1) {
            surf[0] = Math.min(surf[0], seaLevel);
        }

        double[] tmp = new double[n];
        for (int pass = 0; pass < Math.max(0, cfg.bedSmoothingPasses); pass++) {
            System.arraycopy(surf, 0, tmp, 0, n);
            smooth(tmp, cfg.bedSmoothingRadius, 1);
            for (int i = 0; i < n; i++) {
                surf[i] = Math.min(tmp[i], terrain[i]);
            }
            runningMinDownstream(surf);
        }
        return surf;
    }

    /** Makes the profile non-increasing toward index 0 (the mouth). */
    private static void runningMinDownstream(double[] profile) {
        double run = Double.MAX_VALUE;
        for (int i = profile.length - 1; i >= 0; i--) {
            run = Math.min(run, profile[i]);
            profile[i] = run;
        }
    }

    /** Rise per block travelled, measured over a small window. */
    private static double[] slopeProfile(double[] profile) {
        int n = profile.length;
        double[] slope = new double[n];
        int w = 3;
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - w);
            int b = Math.min(n - 1, i + w);
            double run = (b - a) * SAMPLE_STEP;
            slope[i] = run <= 0 ? 0 : Math.abs(profile[b] - profile[a]) / run;
        }
        return slope;
    }

    /**
     * Half-width from the Azgaar width, narrowed on steep ground and smoothed so
     * it does not flicker from section to section. Flat reaches -- the stretches
     * that join oceans and lakes -- are held near the river's own widest point so
     * a wide channel stays wide (and navigable) along its whole flat run.
     */
    private static double[] widthProfile(FMGRiver river, double dNorm, double[] slope,
                                         HeightmapPostConfig.Rivers cfg) {
        int n = slope.length;
        double[] half = new double[n];
        double widest = cfg.minHalfWidth;

        // Flow floor: width grows with the square root of discharge, so a
        // high-flow river stays a big river even where Azgaar's own width value
        // is modest. Azgaar's width still wins wherever it is larger.
        double flowFloor = cfg.minHalfWidth
                + (cfg.maxHalfWidth - cfg.minHalfWidth) * Math.sqrt(clamp(dNorm, 0.0, 1.0)) * 0.5;

        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0 : i / (double) (n - 1);
            double widthKm = river.getWidth() + t * (river.getSourceWidth() - river.getWidth());
            double base = Math.max(widthKm * cfg.kmToBlocks * 0.5, flowFloor * (1.0 - 0.5 * t));
            base = clamp(base, cfg.minHalfWidth, cfg.maxHalfWidth);
            widest = Math.max(widest, base);
            half[i] = base / (1.0 + cfg.slopeNarrowing * slope[i]);
        }
        smooth(half, cfg.widthSmoothing, 2);

        double navFloor = Math.max(cfg.navigableMinHalfWidth, widest * cfg.navigableWidthContinuity);
        for (int i = 0; i < n; i++) {
            if (slope[i] <= cfg.navigableSlope) {
                half[i] = Math.max(half[i], navFloor);
            }
            half[i] = clamp(half[i], cfg.minHalfWidth, cfg.maxHalfWidth);
        }
        smooth(half, Math.max(2, cfg.widthSmoothing / 2), 1);
        return half;
    }

    /** Deep and slow on the flats, shallow on the rapids; flat reaches stay boat-deep. */
    private static double[] depthProfile(double dNorm, double[] halfW, double[] slope,
                                         HeightmapPostConfig.Rivers cfg) {
        int n = slope.length;
        double[] depth = new double[n];
        for (int i = 0; i < n; i++) {
            double d = cfg.minDepth + cfg.dischargeDepth * dNorm + cfg.widthDepth * halfW[i];
            d /= 1.0 + cfg.slopeShallowing * slope[i];
            d = clamp(d, cfg.minDepth, cfg.maxDepth);
            if (slope[i] <= cfg.navigableSlope) {
                d = Math.max(d, cfg.navigableMinDepth);
            }
            depth[i] = d;
        }
        smooth(depth, 4, 1);
        return depth;
    }

    /**
     * Marks a window around every road crossing. The pool level is held constant
     * inside it, so a bridge always spans flat water and the drop lands just
     * outside the crossing instead of under it.
     */
    private static boolean[] roadCrossingWindows(FMGRouteIndex routeIndex, double[] px, double[] pz,
                                                 double[] halfW, HeightmapPostConfig.Rivers cfg) {
        int n = px.length;
        boolean[] frozen = new boolean[n];
        int win = 10;
        for (int i = 0; i < n; i++) {
            if (!routeIndex.anyWithin(px[i], pz[i], halfW[i] + cfg.bankWidth + 6.0)) continue;
            for (int j = Math.max(0, i - win); j <= Math.min(n - 1, i + win); j++) {
                frozen[j] = true;
            }
        }
        return frozen;
    }

    /**
     * Turns the continuous surface profile into flat Minecraft pools. Walking
     * upstream the level only ever rises, by 1 block at a time (rapids) until the
     * slope passes {@code waterfallSlope}, where the rise is taken in one bigger
     * step (a waterfall). Sub-block jitter keeps the steps from being evenly
     * spaced, and the level is held across road crossings so bridges span flat
     * water.
     */
    private static int[] waterProfile(double[] surf, boolean[] frozen, int seaLevel, int maxY,
                                      HeightmapPostConfig.Rivers cfg, long seed) {
        int n = surf.length;
        double[] want = surf.clone();
        for (int i = 1; i < n; i++) {
            want[i] = Math.max(want[i], want[i - 1]);
        }
        double[] slope = slopeProfile(want);

        int[] water = new int[n];
        int level = (int) Math.floor(want[0]);
        for (int i = 0; i < n; i++) {
            if (!frozen[i]) {
                double jitter = (HeightPostProcess.valueNoise(i * SAMPLE_STEP / 24.0, 0.0, seed) * 2.0 - 1.0)
                        * cfg.stepJitter;
                int step = slope[i] >= cfg.waterfallSlope
                        ? Math.max(1, Math.min(cfg.maxWaterfallDrop,
                                (int) Math.round(slope[i] / Math.max(1e-6, cfg.waterfallSlope))))
                        : 1;
                while (want[i] + jitter >= level + step) {
                    level += step;
                }
            }
            water[i] = clamp(level, Math.min(seaLevel, (int) Math.floor(want[0])), maxY - 2);
        }
        return water;
    }

    /**
     * Steeper banks where the river sits deep below the surrounding ground, so a
     * gorge stays a gorge instead of flaring into a huge scar; gentle flare where
     * the ground is already flat.
     */
    private static double bankSlopeAt(FMGMapData map, double[] px, double[] pz, int i, double halfWidth,
                                      int waterY, HeightmapPostConfig.Rivers cfg,
                                      int minY, int maxY, int seaLevel) {
        int n = px.length;
        int a = Math.max(0, i - 1);
        int b = Math.min(n - 1, i + 1);
        double dirX = px[b] - px[a];
        double dirZ = pz[b] - pz[a];
        double dl = Math.hypot(dirX, dirZ);
        if (dl < 1e-6) return cfg.bankSlope;
        double perpX = -dirZ / dl;
        double perpZ = dirX / dl;
        double off = halfWidth + cfg.bankWidth + 6.0;

        int bankL = terrainAt(map, px[i] + perpX * off, pz[i] + perpZ * off, minY, maxY, seaLevel);
        int bankR = terrainAt(map, px[i] - perpX * off, pz[i] - perpZ * off, minY, maxY, seaLevel);
        double rise = Math.max(0, Math.min(bankL, bankR) - waterY);
        double t = clamp(rise / Math.max(1e-6, cfg.bankSteepRise), 0.0, 1.0);
        return cfg.bankSlope + (cfg.bankSlopeSteep - cfg.bankSlope) * t;
    }

    // ---- small array helpers -----------------------------------

    /** In-place box blur, {@code passes} times with the given radius in samples. */
    private static void smooth(double[] values, int radius, int passes) {
        int n = values.length;
        if (radius <= 0 || n < 3) return;
        double[] tmp = new double[n];
        for (int pass = 0; pass < passes; pass++) {
            for (int i = 0; i < n; i++) {
                double sum = 0;
                int count = 0;
                for (int o = -radius; o <= radius; o++) {
                    int j = i + o;
                    if (j < 0 || j >= n) continue;
                    sum += values[j];
                    count++;
                }
                tmp[i] = sum / count;
            }
            System.arraycopy(tmp, 0, values, 0, n);
        }
    }

    /** Fades the first and last tenth to zero, pinning both endpoints. */
    private static void taperEnds(double[] values) {
        int n = values.length;
        int fade = Math.max(2, n / 10);
        for (int i = 0; i < n; i++) {
            double t = Math.min(i / (double) fade, (n - 1 - i) / (double) fade);
            values[i] *= clamp(t, 0.0, 1.0);
        }
    }

    private static void reverse(double[] values) {
        for (int i = 0, j = values.length - 1; i < j; i++, j--) {
            double t = values[i];
            values[i] = values[j];
            values[j] = t;
        }
    }

    private void add(RiverSample s) {
        long key = ChunkPos.toLong(((int) Math.floor(s.x)) >> 4, ((int) Math.floor(s.z)) >> 4);
        byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
    }

    // ---- cross-section -----------------------------------------

    /**
     * Top solid block of the carved valley at {@code dist} blocks from the
     * centreline: a curved channel bed (deepest at the centre, 1 deep at the
     * banks) inside the channel, then a constant-slope V-valley outside it.
     * Continuous at the channel edge.
     */
    public static double valleyFloorY(RiverSample s, double dist) {
        if (dist <= s.halfWidth) {
            double u = s.halfWidth <= 0 ? 1.0 : dist / s.halfWidth;
            double depth = 1.0 + (s.centerDepth - 1.0) * (1.0 - u * u);
            return s.waterSurfaceY - depth;
        }
        return (s.waterSurfaceY - 1.0) + s.bankSlope * (dist - s.halfWidth);
    }

    /**
     * Quadratic smooth minimum: exactly {@code min(a, b)} once they are more than
     * {@code k} apart, softened within that band. Used to blend the valley into
     * untouched terrain without leaving a crease along the rim -- and it never
     * returns more than {@code min(a, b)}, so carving only ever lowers ground.
     */
    public static double smoothMin(double a, double b, double k) {
        if (k <= 0) return Math.min(a, b);
        double h = Math.max(k - Math.abs(a - b), 0.0) / k;
        return Math.min(a, b) - h * h * k * 0.25;
    }

    // ---- query ------------------------------------------------

    /** Nearest river within {@code halfWidth + bankWidth} of the column, or {@code null}. */
    public Hit query(int worldX, int worldZ) {
        if (byChunk.isEmpty()) return null;
        int cx = worldX >> 4;
        int cz = worldZ >> 4;
        RiverSample best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                List<RiverSample> list = byChunk.get(ChunkPos.toLong(cx + dx, cz + dz));
                if (list == null) continue;
                for (RiverSample s : list) {
                    double ddx = worldX - s.x;
                    double ddz = worldZ - s.z;
                    double d2 = ddx * ddx + ddz * ddz;
                    if (d2 < bestDistSq) {
                        bestDistSq = d2;
                        best = s;
                    }
                }
            }
        }
        if (best == null) return null;
        double dist = Math.sqrt(bestDistSq);
        if (dist > best.halfWidth + bankWidth) return null;
        return new Hit(best, dist);
    }

    /**
     * Every sample whose valley can reach into this chunk. The carve collects
     * these once per chunk and then walks the list per column -- the valley is
     * far wider than the channel, so a per-column chunk search would be both
     * wrong (too narrow) and slow.
     */
    public List<RiverSample> collectNear(int chunkX, int chunkZ) {
        if (byChunk.isEmpty()) return List.of();
        int reach = (int) Math.ceil(maxInfluence / 16.0);
        double minX = chunkX << 4;
        double minZ = chunkZ << 4;
        double maxX = minX + 15;
        double maxZ = minZ + 15;

        List<RiverSample> out = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                List<RiverSample> list = byChunk.get(ChunkPos.toLong(chunkX + dx, chunkZ + dz));
                if (list == null) continue;
                for (RiverSample s : list) {
                    double ddx = s.x < minX ? minX - s.x : (s.x > maxX ? s.x - maxX : 0);
                    double ddz = s.z < minZ ? minZ - s.z : (s.z > maxZ ? s.z - maxZ : 0);
                    double influence = s.halfWidth + maxValleyRadius + valleySmoothK;
                    if (ddx * ddx + ddz * ddz <= influence * influence) {
                        out.add(s);
                    }
                }
            }
        }
        return out;
    }

    public boolean isEmpty() {
        return byChunk.isEmpty();
    }

    // ---- bake persistence ----

    /** Install a pre-built (baked) network so {@link #get} skips the rebuild. */
    public static void seed(String fmgExport, FMGRiverNetwork net) {
        CACHE.putIfAbsent(fmgExport, net);
    }

    public void writeTo(java.io.DataOutputStream out) throws java.io.IOException {
        out.writeDouble(bankWidth);
        out.writeDouble(maxValleyRadius);
        out.writeDouble(valleySmoothK);
        out.writeDouble(maxInfluence);
        java.util.List<RiverSample> all = new ArrayList<>();
        for (List<RiverSample> bucket : byChunk.values()) {
            all.addAll(bucket);
        }
        out.writeInt(all.size());
        for (RiverSample s : all) {
            out.writeDouble(s.x);
            out.writeDouble(s.z);
            out.writeDouble(s.halfWidth);
            out.writeInt(s.waterSurfaceY);
            out.writeInt(s.centerDepth);
            out.writeDouble(s.bankSlope);
        }
    }

    public static FMGRiverNetwork readFrom(java.io.DataInputStream in) throws java.io.IOException {
        FMGRiverNetwork net = new FMGRiverNetwork(in.readDouble(), in.readDouble(), in.readDouble());
        net.maxInfluence = in.readDouble();
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            double x = in.readDouble();
            double z = in.readDouble();
            double halfWidth = in.readDouble();
            int waterSurfaceY = in.readInt();
            int centerDepth = in.readInt();
            double bankSlope = in.readDouble();
            net.add(new RiverSample(x, z, halfWidth, waterSurfaceY, centerDepth, bankSlope));
        }
        return net;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
