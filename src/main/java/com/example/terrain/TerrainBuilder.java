package com.example.terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Builds the blended terrain from an FMG map: flat, biome-shaped lowlands;
 * eroded mountains; rivers and lakes sitting in their own valleys. A port of
 * refinemap/terrain.py (the Python workbench), stage for stage:
 *
 * <ol>
 *   <li>base: de-faceted macro shape through an elevation curve, fractal coast, ocean floor</li>
 *   <li>lowland: per-biome pattern noise (dunes, mesas, drumlins...) mixed across smoothed biome borders</li>
 *   <li>rivers: smoothed, meandering FMG paths burned in as guide trenches</li>
 *   <li>mountains: ridged relief growing with elevation, then stream-power erosion</li>
 *   <li>weight: altitude x coast-calm, carried down the drainage network so valleys fade out gradually</li>
 *   <li>blend: base + lerp(lowland, eroded - base, weight)</li>
 *   <li>lakes at their FMG level; rivers one by one (biggest first) with a water
 *       profile read from the ground and an eased floodplain valley</li>
 *   <li>roads graded in (cut and fill) along their corridor</li>
 *   <li>masks for the per-block detail the chunk generator adds</li>
 * </ol>
 */
public final class TerrainBuilder {

    public interface Progress {
        void report(String phase, float fraction);
    }

    /** Extra rasters for previews (the mod doesn't need them). */
    public static final class Preview {
        public int w, h;
        public float[] water;       // sea / lake / river surface, NaN = dry
        public byte[] biome;
        public float[] weight;
    }

    // biome style table: roll, hum, dune, mesa, insel, drum, flat, ero, bias
    private static final int ROLL = 0, HUM = 1, DUNE = 2, MESA = 3, INSEL = 4, DRUM = 5, FLAT = 6, ERO = 7, BIAS = 8;
    private static final double[][] STYLES = {
            {3.5, 1.1, 0, 0, 0, 0, 0, 1.0, 0},        // 0 Marine / unknown
            {1.5, 0.5, 4.2, 0, 0, 0, 0, 0.55, 0},     // 1 Hot desert: dunes
            {2.5, 0.8, 0, 9.0, 0, 0, 0, 0.7, 0},      // 2 Cold desert: terraced mesas
            {2.2, 0.7, 0, 0, 7.0, 0, 0, 0.75, 0},     // 3 Savanna: rock knolls on a plain
            {2.0, 0.6, 0, 0, 0, 0, 0.3, 1.0, 0},      // 4 Grassland
            {3.5, 1.0, 0, 0, 3.0, 0, 0, 1.0, 0},      // 5 Tropical seasonal forest
            {4.0, 1.3, 0, 0, 0, 0, 0, 1.0, 0},        // 6 Temperate deciduous forest
            {5.5, 1.8, 0, 0, 0, 0, 0, 1.0, 0.12},     // 7 Tropical rainforest
            {5.0, 1.6, 0, 0, 0, 0, 0, 1.0, 0.10},     // 8 Temperate rainforest
            {2.5, 1.1, 0, 0, 0, 3.0, 0, 1.0, 0},      // 9 Taiga: drumlins
            {2.0, 0.9, 0, 0, 0, 3.2, 0.3, 1.0, 0},    // 10 Tundra
            {1.8, 0.3, 0, 0, 0, 0, 0.4, 0.6, 0},      // 11 Glacier
            {0.8, 0.35, 0, 0, 0, 0, 0.85, 0.5, 0},    // 12 Wetland
    };
    private static final int STYLE_N = 9;
    private static final float FMG_SEA = 20f;

    private final TerrainInput in;
    private final TerrainParams p;
    private final Progress progress;
    private final boolean carveChannels;

    private int W, H, N;
    private double dx, S, sea, P;
    private TerrainNoise noise;
    private SplittableRandom rng;

    private TerrainBuilder(TerrainInput in, TerrainParams p, boolean carveChannels, Progress progress) {
        this.in = in;
        this.p = p;
        this.carveChannels = carveChannels;
        this.progress = progress == null ? (a, b) -> { } : progress;
    }

    /** Build the terrain for the mod. */
    public static TerrainRaster build(TerrainInput in, TerrainParams p, Progress progress) {
        return new TerrainBuilder(in, p, false, progress).run(null);
    }

    /** Build with river channels carved into the raster and preview layers filled in. */
    public static TerrainRaster buildPreview(TerrainInput in, TerrainParams p, Preview preview, Progress progress) {
        return new TerrainBuilder(in, p, true, progress).run(preview);
    }

    private static final class Path {
        int id, parent;
        double dq;
        double[] x, y;        // cell coordinates
        double[] hw;          // channel half-width, blocks
        double[] wl;          // water surface
    }

    private TerrainRaster run(Preview preview) {
        dx = p.cellSize > 0 ? p.cellSize : Math.max(4.0, in.scale / 2.0);
        while (Math.round(in.fmgW * in.scale / dx) * Math.round(in.fmgH * in.scale / dx) > p.maxCells) dx *= 1.1;
        S = in.scale / dx;
        W = (int) Math.round(in.fmgW * S);
        H = (int) Math.round(in.fmgH * S);
        N = W * H;
        sea = in.seaLevel;
        P = p.peak;
        noise = new TerrainNoise(in.seed);
        rng = new SplittableRandom(in.seed ^ 0x5DEECE66DL);
        int fw = in.fmgW, fh = in.fmgH, fn = fw * fh;

        // ---- base ------------------------------------------------------------
        progress.report("Shaping terrain: base", 0.00f);
        boolean[] land0 = new boolean[fn];
        float[] tsrc = new float[fn];
        float[] land0f = new float[fn];
        for (int i = 0; i < fn; i++) {
            land0[i] = in.height[i] >= FMG_SEA;
            tsrc[i] = land0[i] ? (in.height[i] - FMG_SEA) / (100f - FMG_SEA) : 0f;
            land0f[i] = land0[i] ? 1f : 0f;
        }
        float[] tup = Grid.resizeCubic(Grid.maskedGaussian(tsrc, land0, fw, fh, p.defacet), fw, fh, W, H);
        for (int k = 0; k < N; k++) tup[k] = (float) Grid.clamp(tup[k], 0, 1);
        double tmax = Math.max(Grid.max(tup), 1e-3);

        float[] msoft = Grid.resizeCubic(Grid.gaussian(land0f, fw, fh, p.coastBlur), fw, fh, W, H);
        float[] cn = noise.fbm(W, H, dx / 900.0, 5, 11, 7);
        boolean[] land = new boolean[N];
        for (int k = 0; k < N; k++) land[k] = msoft[k] + cn[k] * p.coastRough > 0.5;
        land = Grid.open3(land, W, H);
        msoft = null;
        cn = null;

        float[] lakeUp = Grid.resizeNearest(in.lakeLevel, fw, fh, W, H);
        boolean[] lake = new boolean[N];
        boolean[] ocean = new boolean[N];
        boolean[] notLand = new boolean[N];
        boolean[] notOcean = new boolean[N];
        for (int k = 0; k < N; k++) {
            lake[k] = !land[k] && lakeUp[k] > FMG_SEA + 0.5f;
            ocean[k] = !land[k] && !lake[k];
            notLand[k] = !land[k];
            notOcean[k] = !ocean[k];
        }
        float[] dOcean = scale(Grid.edt(ocean, W, H, null), dx);      // blocks to the sea
        float[] dLand = scale(Grid.edt(notLand, W, H, null), dx);     // blocks to any water
        float[] dSea = scale(Grid.edt(notOcean, W, H, null), dx);     // blocks out to sea

        float[] base = new float[N];
        float[] lakeY = new float[N];
        for (int k = 0; k < N; k++) {
            double b = sea + 1 + curve(tup[k] / tmax);
            b = sea + 1 + (b - sea - 1) * Grid.smoothstep(0, p.coastRamp, dOcean[k]);
            if (ocean[k]) {
                b = sea - 2 - p.oceanDepth * Math.pow(Grid.smoothstep(0, p.shelf, dSea[k]), 0.6);
            }
            base[k] = (float) b;
            lakeY[k] = lakeUp[k] > FMG_SEA + 0.5f
                    ? (float) (sea + curve((lakeUp[k] - FMG_SEA) / (100.0 - FMG_SEA) / tmax)) : (float) sea;
        }
        tup = null;
        lakeUp = null;
        dSea = null;

        // biome styles, mixed at FMG resolution and sampled bilinearly
        float[][] style = biomeStyles(fw, fh);

        // ---- lowland patterns -------------------------------------------------
        progress.report("Shaping terrain: lowlands", 0.05f);
        float[] lowland = new float[N];
        float[] f = noise.fbm(W, H, dx / 320.0, 4, 21, 5);
        Grid.normalise(f, land);
        addStyled(lowland, f, style, ROLL, 1.0);
        f = noise.fbm(W, H, dx / 56.0, 3, 23, 9);
        Grid.normalise(f, land);
        addStyled(lowland, f, style, HUM, 1.0);
        f = noise.fbm(W, H, dx / 900.0, 3, 25, 1);
        Grid.normalise(f, land);
        for (int k = 0; k < N; k++) {
            double gain = Grid.smoothstep(0.01 * P, 0.22 * P, base[k] - sea) * (1 - styleAt(style, FLAT, k));
            lowland[k] += (float) (p.hillAmp * gain * f[k]);
        }
        double wind = rng.nextDouble() * Math.PI;
        f = noise.dunes(W, H, dx, p.duneWavelength, wind);
        float[] duneSea = noise.fbm(W, H, dx / 2500.0, 3, 61, 3);
        for (int k = 0; k < N; k++) f[k] *= (float) Grid.smoothstep(-0.3, 0.3, duneSea[k]);
        duneSea = null;
        addStyled(lowland, f, style, DUNE, 2.0);
        f = noise.fbm(W, H, dx / 700.0, 4, 71, 17);
        Grid.normalise(f, land);
        for (int k = 0; k < N; k++) f[k] = (float) (terrace(Math.max(f[k] + 0.3, 0) * 3.0, 1.0) / 3.0);
        addStyled(lowland, f, style, MESA, 3.0);
        f = noise.fbm(W, H, dx / 450.0, 4, 81, 29);
        Grid.normalise(f, land);
        for (int k = 0; k < N; k++) f[k] = (float) (Math.pow(Grid.smoothstep(1.1, 2.4, f[k]), 1.5) * 3.0);
        addStyled(lowland, f, style, INSEL, 1.0);
        f = noise.aniso(W, H, dx, 260, 70, wind + 0.4, 3, 91);
        Grid.normalise(f, land);
        for (int k = 0; k < N; k++) f[k] = Math.max(f[k], -0.5f);
        addStyled(lowland, f, style, DRUM, 1.0);
        f = null;
        for (int k = 0; k < N; k++) {
            lowland[k] = land[k] ? lowland[k] * (float) Grid.smoothstep(0, 60, dLand[k]) : 0f;
        }

        // ---- rivers: paths + guide trench ------------------------------------------
        progress.report("Shaping terrain: river paths", 0.10f);
        List<Path> paths = riverPaths(base);
        float[] burn = Grid.fill(N, Float.POSITIVE_INFINITY);
        if (!paths.isEmpty()) {
            float[] bedR = Grid.fill(N, Float.POSITIVE_INFINITY);
            float[] hwR = new float[N];
            for (Path pa : paths) {
                double run = Double.POSITIVE_INFINITY;
                for (int i = 0; i < pa.x.length; i++) {
                    run = Math.min(run, Grid.bilinear(base, W, H, pa.x[i], pa.y[i]));
                    int k = cell(pa.x[i], pa.y[i]);
                    float bed = (float) Math.max(run - 2, sea - 1);
                    if (k >= 0 && bed < bedR[k]) {
                        bedR[k] = bed;
                        hwR[k] = (float) pa.hw[i];
                    }
                }
            }
            boolean[] hit = new boolean[N];
            for (int k = 0; k < N; k++) hit[k] = bedR[k] < Float.POSITIVE_INFINITY;
            int[] near = new int[N];
            float[] dist = Grid.edt(hit, W, H, near);
            for (int k = 0; k < N; k++) {
                double d = dist[k] * dx;
                int q = near[k];
                if (d < hwR[q] + p.burnReach) burn[k] = (float) (bedR[q] + Math.max(d - hwR[q], 0) * p.burnSlope);
            }
        }

        // ---- mountains: ridged relief + erosion ------------------------------------
        progress.report("Shaping terrain: mountains", 0.14f);
        double wf = dx / p.feature;
        float[] wx = noise.fbm(W, H, wf * 0.5, 4, 100, 0);
        float[] wy = noise.fbm(W, H, wf * 0.5, 4, 0, 100);
        float[] ridge = noise.ridged(W, H, wf, 7, wx, wy, p.feature / dx * 0.8, 2.0);
        wx = null;
        wy = null;
        Grid.normalise(ridge, land);
        double[] z = new double[N];
        float[] floor = new float[N];
        for (int k = 0; k < N; k++) {
            double rel = base[k] - sea;
            double mount = Grid.smoothstep(0.04 * P, 0.55 * P, rel);
            double v = base[k] + p.relief * P * Math.pow(mount, 1.3) * ridge[k] + lowland[k];
            if (land[k]) {
                if (burn[k] < Float.POSITIVE_INFINITY) v = Grid.smin(v, Math.max(burn[k], sea + 1), 8.0);
                v = Math.max(v, sea + 1);
            } else {
                v = base[k];
            }
            z[k] = v;
            floor[k] = (float) Math.max(v - (p.maxCarveLow * styleAt(style, ERO, k) + p.maxCarve * mount), sea + 1);
        }
        ridge = null;
        burn = null;

        boolean[] outlet = notLand;
        Erosion.Flow flow = new Erosion.Flow(N);
        for (int it = 0; it < p.iterations; it++) {
            Erosion.floodRoute(z, outlet, W, H, flow);
            Erosion.steepestReceivers(flow, outlet, W, H, in.seed + it);
            double[] zn = Erosion.streamPower(flow, dx * dx, p.erodibility, dx, p.channelArea);
            for (int k = 0; k < N; k++) {
                if (land[k]) z[k] = Math.max(zn[k], floor[k]);
            }
            float[] zb = Grid.gaussian(toFloat(z), W, H, 1.0);
            for (int k = 0; k < N; k++) {
                if (land[k]) z[k] += p.diffusion * (zb[k] - z[k]);
            }
            if (it % 6 == 5) z = Erosion.thermal(z, W, H, p.talus * dx, 0.5, outlet);
            progress.report("Shaping terrain: eroding mountains", 0.15f + 0.60f * (it + 1) / Math.max(1, p.iterations));
        }
        for (int i = 0; i < 3; i++) z = Erosion.thermal(z, W, H, p.talus * dx, 0.5, outlet);
        float[] eroded = toFloat(z);
        z = null;
        floor = null;

        // ---- blend weight ------------------------------------------------------------
        progress.report("Shaping terrain: blending", 0.76f);
        float[] slopeBase = slope(Grid.gaussian(base, W, H, 4.0));
        float[] wH = new float[N];
        float[] w0 = new float[N];
        for (int k = 0; k < N; k++) {
            double cliff = Grid.smoothstep(0.15, 0.35, slopeBase[k]);
            double calm = Grid.smoothstep(p.coastCalmStart, p.coastCalmEnd, dOcean[k]);
            calm = calm + (1 - calm) * cliff;
            wH[k] = (float) Grid.smoothstep(p.mountainStart * P, p.mountainFull * P, base[k] - sea);
            w0[k] = land[k] ? (float) (Grid.clamp(wH[k] + styleAt(style, BIAS, k) * (1 - wH[k]), 0, 1) * calm) : 0f;
        }
        slopeBase = null;
        float[] w = Grid.gaussian(Erosion.valleyWeight(w0, flow, dx, p.valleyArea, p.valleyReach, p.valleySide), W, H, 1.5);
        flow = null;
        w0 = null;

        // ---- blend ----------------------------------------------------------------------
        float[] ed = new float[N];
        for (int k = 0; k < N; k++) ed[k] = eroded[k] - base[k];
        float[] edSoft = Grid.gaussian(ed, W, H, p.valleySoften / dx);
        float[] hgt = new float[N];
        for (int k = 0; k < N; k++) {
            double e = edSoft[k] + (ed[k] - edSoft[k]) * Grid.smoothstep(0.0, 0.6, wH[k]);
            double wk = land[k] ? Grid.clamp(w[k], 0, 1) : 0;
            double v = base[k] + lowland[k] * (1 - wk) + e * wk;
            hgt[k] = land[k] ? (float) Math.max(v, sea + 1) : base[k];
        }
        ed = edSoft = eroded = lowland = null;

        // mountain compression: lower, gentler mountains; lowlands below the knee untouched
        for (int k = 0; k < N; k++) {
            if (land[k]) hgt[k] = (float) (sea + p.compress(hgt[k] - sea));
            lakeY[k] = (float) (sea + p.compress(lakeY[k] - sea));
        }

        float[] water = new float[N];
        for (int k = 0; k < N; k++) water[k] = ocean[k] ? (float) sea : Float.NaN;

        // ---- lakes -----------------------------------------------------------------------
        boolean anyLake = false;
        for (boolean b : lake) {
            if (b) {
                anyLake = true;
                break;
            }
        }
        boolean[] notLake = new boolean[N];
        for (int k = 0; k < N; k++) notLake[k] = !lake[k];
        if (anyLake) {
            float[] dIn = scale(Grid.edt(notLake, W, H, null), dx);
            int[] near = new int[N];
            float[] dl = Grid.edt(lake, W, H, near);
            for (int k = 0; k < N; k++) {
                double d = dl[k] * dx;
                if (land[k] && d < p.lakeShore) {
                    hgt[k] = (float) Math.max(hgt[k], lakeY[near[k]] + 1 + d * 0.02);
                } else if (lake[k]) {
                    hgt[k] = (float) (lakeY[k] - Math.min(2 + dIn[k] * 0.12, 24));
                    water[k] = lakeY[k];
                }
            }
        }

        // ---- rivers: water profile + floodplain, biggest first ----------------------------
        progress.report("Shaping terrain: river valleys", 0.80f);
        float[] fpNoise = noise.fbm(W, H, dx / 90.0, 3, 131, 7);
        for (int k = 0; k < N; k++) fpNoise[k] = Math.max(0f, fpNoise[k]) * 1.5f;
        boolean[] riverCore = new boolean[N];
        TerrainRaster out = new TerrainRaster(W, H, in.originX, in.originZ, dx, in.seaLevel);
        Map<Integer, Path> byId = new HashMap<>();
        for (Path pa : paths) {
            carveRiver(pa, byId, hgt, land, lake, ocean, lakeY, wH, fpNoise, riverCore, water);
            byId.put(pa.id, pa);
            TerrainRaster.River rv = new TerrainRaster.River();
            rv.id = pa.id;
            rv.parent = pa.parent;
            int n = pa.x.length;
            rv.x = new float[n];
            rv.z = new float[n];
            rv.waterY = new float[n];
            rv.halfWidth = new float[n];
            for (int i = 0; i < n; i++) {
                rv.x[i] = (float) (in.originX + (pa.x[i] + 0.5) * dx);
                rv.z[i] = (float) (in.originZ + (pa.y[i] + 0.5) * dx);
                rv.waterY[i] = (float) pa.wl[i];
                rv.halfWidth[i] = (float) pa.hw[i];
            }
            out.rivers.add(rv);
        }
        fpNoise = null;

        // ---- roads -------------------------------------------------------------------------
        if (p.gradeRoads && !in.routes.isEmpty()) {
            progress.report("Shaping terrain: grading roads", 0.90f);
            gradeRoads(hgt, land, lake, riverCore);
        }

        // ---- fit under the build limit, detail masks, lake water ----------------------------
        progress.report("Shaping terrain: finishing", 0.96f);
        double limit = in.maxY - 1 - p.topMargin;
        double knee = limit - 64;
        for (int k = 0; k < N; k++) {
            double v = hgt[k];
            if (v > knee) v = knee + (limit - knee) * Math.tanh((v - knee) / (limit - knee));
            hgt[k] = (float) Math.max(v, in.minY + 2);
        }
        System.arraycopy(hgt, 0, out.height, 0, N);

        float[] sl = slope(hgt);
        boolean[] wet = new boolean[N];
        for (int k = 0; k < N; k++) wet[k] = !land[k] || riverCore[k];
        float[] dWet = scale(Grid.edt(wet, W, H, null), dx);
        for (int k = 0; k < N; k++) {
            double steep = Grid.smoothstep(0.55, 1.4, sl[k]);
            double calm = Grid.smoothstep(4, 24, dWet[k]);
            out.fineMask[k] = (byte) Math.round(255 * (1 - steep) * calm);
            out.cragMask[k] = (byte) Math.round(255 * steep * Grid.smoothstep(2, 10, dWet[k]));
        }
        if (anyLake) {
            int[] near = new int[N];
            float[] dl = Grid.edt(lake, W, H, near);
            for (int k = 0; k < N; k++) {
                if (dl[k] <= 2.01f) out.lakeWaterY[k] = (short) Math.floor(lakeY[near[k]]);
            }
        }
        out.fineAmp = p.fineDetail;
        out.cragAmp = p.cragDetail;
        out.detailSeed = in.seed * 0x9E3779B97F4A7C15L + 0x51ED27L;

        if (preview != null) {
            preview.w = W;
            preview.h = H;
            preview.water = water;
            preview.weight = w;
            preview.biome = new byte[N];
            for (int j = 0; j < H; j++) {
                int sy = Math.min(fh - 1, (int) ((j + 0.5) / S));
                for (int i = 0; i < W; i++) {
                    int sx = Math.min(fw - 1, (int) ((i + 0.5) / S));
                    preview.biome[j * W + i] = in.biome[sy * fw + sx];
                }
            }
        }
        progress.report("Shaping terrain: done", 1f);
        return out;
    }

    // ---- stages -----------------------------------------------------------------------------

    private double curve(double t) {
        return P * Math.pow(Grid.clamp(t, 0, 1), p.gamma);
    }

    private float[][] biomeStyles(int fw, int fh) {
        int fn = fw * fh;
        float[][] style = new float[STYLE_N][fn];
        float[] wsum = new float[fn];
        for (int b = 0; b < STYLES.length; b++) {
            float[] onehot = new float[fn];
            boolean any = false;
            for (int i = 0; i < fn; i++) {
                int id = in.biome[i] & 0xFF;
                if (id >= STYLES.length) id = 0;
                if (id == b) {
                    onehot[i] = 1;
                    any = true;
                }
            }
            if (!any) continue;
            float[] wb = Grid.gaussian(onehot, fw, fh, p.biomeBlend / in.scale);
            for (int i = 0; i < fn; i++) {
                float v = Math.max(0, wb[i]);
                wsum[i] += v;
                for (int s = 0; s < STYLE_N; s++) style[s][i] += (float) (v * STYLES[b][s]);
            }
        }
        for (int s = 0; s < STYLE_N; s++) {
            for (int i = 0; i < fn; i++) style[s][i] /= wsum[i] + 1e-6f;
        }
        return style;
    }

    private double styleAt(float[][] style, int s, int k) {
        int j = k / W, i = k - j * W;
        return Grid.bilinear(style[s], in.fmgW, in.fmgH, (i + 0.5) / S - 0.5, (j + 0.5) / S - 0.5);
    }

    private void addStyled(float[] acc, float[] pattern, float[][] style, int s, double mul) {
        for (int k = 0; k < N; k++) {
            double c = styleAt(style, s, k);
            if (c != 0) acc[k] += (float) (c * mul * pattern[k]);
        }
    }

    private static double terrace(double n, double step) {
        double q = n / step;
        double fl = Math.floor(q);
        return (fl + Grid.smoothstep(0.78, 1.0, q - fl)) * step;
    }

    private List<Path> riverPaths(float[] base) {
        RouteSegments roads = new RouteSegments(in.routes, S);
        double maxq = 1;
        for (TerrainInput.River r : in.rivers) maxq = Math.max(maxq, r.discharge);
        List<TerrainInput.River> sorted = new ArrayList<>(in.rivers);
        sorted.sort(Comparator.comparingDouble(r -> -r.discharge));
        List<Path> out = new ArrayList<>();
        for (TerrainInput.River r : sorted) {
            if (r.x == null || r.x.length < 2) continue;
            double[] cx = new double[r.x.length], cy = new double[r.x.length];
            for (int i = 0; i < cx.length; i++) {
                cx[i] = r.x[i] * S - 0.5;
                cy[i] = r.y[i] * S - 0.5;
            }
            double[][] xy = catmullRom(cx, cy, 0.5);
            double[] x = xy[0], y = xy[1];
            int n = x.length;
            boolean inside = false;
            for (int i = 0; i < n && !inside; i++) inside = x[i] > -20 && y[i] > -20 && x[i] < W + 20 && y[i] < H + 20;
            if (n < 8 || !inside) continue;

            // meander: perpendicular wander, strongest on flat, low ground
            double[] zb = new double[n];
            for (int i = 0; i < n; i++) zb[i] = Grid.bilinear(base, W, H, x[i], y[i]);
            double[] fl = new double[n];
            for (int i = 0; i < n; i++) {
                double g = Math.abs(gradient(zb, i)) / (0.5 * dx);
                fl[i] = (1 - Grid.smoothstep(0.02, 0.10, g)) * (1 - Grid.smoothstep(0.1 * P, 0.35 * P, zb[i] - sea));
            }
            fl = smooth1d(fl, 20, 1);
            double ph0 = rng.nextDouble() * 2 * Math.PI, ph1 = rng.nextDouble() * 2 * Math.PI;
            double ph2 = rng.nextDouble() * 2 * Math.PI;
            double L = p.meanderWavelength * (0.8 + 0.45 * rng.nextDouble());
            double[] nx = new double[n], ny = new double[n];
            for (int i = 0; i < n; i++) {
                double gx = gradient(x, i), gy = gradient(y, i);
                double gl = Math.hypot(gx, gy) + 1e-9;
                double s = i * 0.5 * dx;
                double wav = Math.sin(2 * Math.PI * s / L + ph0 + 0.9 * Math.sin(2 * Math.PI * s / (3.7 * L) + ph1));
                wav = 0.8 * wav + 0.2 * Math.sin(2 * Math.PI * s / (0.43 * L) + ph2);
                double taper = Grid.clamp(Math.min(i, n - 1 - i) / 40.0, 0, 1);
                double off = wav * p.meander / dx * fl[i] * taper;
                nx[i] = x[i] - gy / gl * off;
                ny[i] = y[i] + gx / gl * off;
            }
            Path pa = new Path();
            pa.id = r.id;
            pa.parent = r.parent;
            pa.x = nx;
            pa.y = ny;
            pa.dq = r.discharge / maxq;
            double[] hw = new double[n];
            for (int i = 0; i < n; i++) {
                double t = n == 1 ? 0 : i / (double) (n - 1);
                double wkm = r.sourceWidthKm + (r.widthKm - r.sourceWidthKm) * t;
                hw[i] = Grid.clamp(Math.max(wkm * p.kmToBlocks * 0.5, 2.5 + 9 * Math.sqrt(pa.dq) * t), 2.5, 24);
            }
            pa.hw = smooth1d(hw, 10, 1);
            keepClearOfRoads(pa, roads);
            out.add(pa);
        }
        return out;
    }

    /**
     * FMG roads and rivers often follow the same cells, so a road running along
     * a valley ends up on top of its river -- a long strip of bridge along the
     * bank. Where a road runs roughly parallel (within ~45 degrees) and closer
     * than the channel + the road's shoulder + {@code riverRoadClearance}, the
     * river is moved out to that distance on the side of the road it mostly runs
     * on -- decided over the whole stretch, so a river that meandered back and
     * forth across the road ends up beside it instead. Real crossings (steep
     * angle) are left where they are.
     */
    private void keepClearOfRoads(Path pa, RouteSegments roads) {
        int n = pa.x.length;
        if (roads.isEmpty() || n < 8 || p.riverRoadClearance <= 0) return;
        double[] hit = new double[6];
        for (int pass = 0; pass < 4; pass++) {
            double[] qx = new double[n], qy = new double[n], rnx = new double[n], rny = new double[n];
            double[] keepC = new double[n], wgt = new double[n], side = new double[n];
            for (int i = 0; i < n; i++) {
                double gx = gradient(pa.x, i), gy = gradient(pa.y, i);
                double gl = Math.hypot(gx, gy);
                if (gl < 1e-9) continue;
                gx /= gl;
                gy /= gl;
                double keep = (pa.hw[i] + 8 + p.riverRoadClearance) / dx;
                if (!roads.nearest(pa.x[i], pa.y[i], keep * 1.5 + 1, hit)) continue;
                double wc = Grid.smoothstep(0.65, 0.85, Math.abs(gx * hit[1] + gy * hit[2]));
                if (wc <= 0) continue;
                // road normal, oriented like the river's normal so "sides" stay consistent along the river
                double nx = -hit[2], ny = hit[1];
                if (nx * -gy + ny * gx < 0) {
                    nx = -nx;
                    ny = -ny;
                }
                qx[i] = hit[3];
                qy[i] = hit[4];
                rnx[i] = nx;
                rny[i] = ny;
                keepC[i] = keep;
                wgt[i] = wc * (1 - Grid.smoothstep(keep, keep * 1.5, hit[0]));
                side[i] = ((pa.x[i] - hit[3]) * nx + (pa.y[i] - hit[4]) * ny) * wc;
            }
            side = smooth1d(side, 30, 2);                    // the side the river mostly runs on
            double[] mx = new double[n], my = new double[n];
            for (int i = 0; i < n; i++) {
                if (wgt[i] <= 0) continue;
                double sgn = side[i] >= 0 ? 1 : -1;
                double cur = (pa.x[i] - qx[i]) * rnx[i] + (pa.y[i] - qy[i]) * rny[i];
                if (cur * sgn >= keepC[i]) continue;         // already clear on that side
                double tx = qx[i] + rnx[i] * sgn * keepC[i], ty = qy[i] + rny[i] * sgn * keepC[i];
                mx[i] = (tx - pa.x[i]) * wgt[i];
                my[i] = (ty - pa.y[i]) * wgt[i];
            }
            mx = smooth1d(mx, 6, 2);
            my = smooth1d(my, 6, 2);
            double[] moved = new double[n];
            for (int i = 0; i < n; i++) {
                double taper = Grid.clamp(Math.min(i, n - 1 - i) / 12.0, 0, 1);
                pa.x[i] += mx[i] * taper;
                pa.y[i] += my[i] * taper;
                moved[i] = Math.min(1, Math.hypot(mx[i], my[i]) * taper);
            }
            // relax the moved stretches so the shifted path cannot fold into loops
            moved = smooth1d(moved, 6, 1);
            for (int k = 0; k < 3; k++) {
                double[] x0 = pa.x.clone(), y0 = pa.y.clone();
                for (int i = 1; i < n - 1; i++) {
                    double a = 0.5 * moved[i];
                    pa.x[i] = x0[i] + a * (0.5 * (x0[i - 1] + x0[i + 1]) - x0[i]);
                    pa.y[i] = y0[i] + a * (0.5 * (y0[i - 1] + y0[i + 1]) - y0[i]);
                }
            }
        }
    }

    /** Route polylines as short segments in cell coordinates, bucketed for nearest queries. */
    private static final class RouteSegments {
        private final List<double[]> segs = new ArrayList<>();      // ax, ay, bx, by
        private final Map<Long, List<Integer>> buckets = new HashMap<>();
        private static final double B = 16;

        RouteSegments(List<TerrainInput.Route> routes, double s) {
            for (TerrainInput.Route r : routes) {
                if (r.x == null || r.x.length < 2) continue;
                double px = r.x[0] * s - 0.5, py = r.y[0] * s - 0.5;
                for (int i = 1; i < r.x.length; i++) {
                    double qx = r.x[i] * s - 0.5, qy = r.y[i] * s - 0.5;
                    if (Math.hypot(qx - px, qy - py) < 1.0 && i < r.x.length - 1) continue;
                    int id = segs.size();
                    segs.add(new double[]{px, py, qx, qy});
                    long key = key((int) Math.floor((px + qx) * 0.5 / B), (int) Math.floor((py + qy) * 0.5 / B));
                    buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(id);
                    px = qx;
                    py = qy;
                }
            }
        }

        boolean isEmpty() {
            return segs.isEmpty();
        }

        private static long key(int bx, int by) {
            return ((long) bx << 32) ^ (by & 0xFFFFFFFFL);
        }

        /** Nearest segment within r cells: out = {distance, dirX, dirY, closestX, closestY}. */
        boolean nearest(double x, double y, double r, double[] out) {
            int reach = (int) Math.ceil((r + 2) / B);
            int bx = (int) Math.floor(x / B), by = (int) Math.floor(y / B);
            double best = r;
            boolean found = false;
            for (int j = by - reach; j <= by + reach; j++) {
                for (int i = bx - reach; i <= bx + reach; i++) {
                    List<Integer> list = buckets.get(key(i, j));
                    if (list == null) continue;
                    for (int id : list) {
                        double[] sg = segs.get(id);
                        double vx = sg[2] - sg[0], vy = sg[3] - sg[1];
                        double l2 = vx * vx + vy * vy;
                        double t = l2 <= 0 ? 0 : Grid.clamp(((x - sg[0]) * vx + (y - sg[1]) * vy) / l2, 0, 1);
                        double cx = sg[0] + t * vx, cy = sg[1] + t * vy;
                        double d = Math.hypot(x - cx, y - cy);
                        if (d < best) {
                            double l = Math.sqrt(l2) + 1e-9;
                            best = d;
                            out[0] = d;
                            out[1] = vx / l;
                            out[2] = vy / l;
                            out[3] = cx;
                            out[4] = cy;
                            found = true;
                        }
                    }
                }
            }
            return found;
        }
    }

    private void carveRiver(Path pa, Map<Integer, Path> byId, float[] hgt, boolean[] land, boolean[] lake,
                            boolean[] ocean, float[] lakeY, float[] wH, float[] fpNoise,
                            boolean[] riverCore, float[] water) {
        int n = pa.x.length;
        double[] zg = new double[n];
        for (int i = 0; i < n; i++) zg[i] = Grid.bilinear(hgt, W, H, pa.x[i], pa.y[i]);
        // a river never climbs: running min source -> mouth, smoothed, re-clamped
        double[] wl = new double[n];
        double run = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) wl[i] = run = Math.min(run, zg[i] - 1);
        for (int pass = 0; pass < 4; pass++) {
            double[] s = smooth1d(wl, 6, 1);
            run = Double.POSITIVE_INFINITY;
            for (int i = 0; i < n; i++) wl[i] = run = Math.min(run, Math.min(s[i], zg[i] - 1));
        }
        Path par = byId.get(pa.parent);
        if (par != null && pa.parent != pa.id) {             // meet the parent at its level
            int best = 0;
            double bd = Double.MAX_VALUE;
            for (int j = 0; j < par.x.length; j++) {
                double d = sq(par.x[j] - pa.x[n - 1]) + sq(par.y[j] - pa.y[n - 1]);
                if (d < bd) {
                    bd = d;
                    best = j;
                }
            }
            for (int i = 0; i < n; i++) wl[i] = Math.max(wl[i], par.wl[best]);
        }
        int end = cellClamped(pa.x[n - 1], pa.y[n - 1]);
        if (ocean[end] || zg[n - 1] <= sea + 2) {
            for (int i = 0; i < n; i++) wl[i] = Math.max(wl[i], sea);
        }
        // never below a lake further downstream (it would sit under the lake surface)
        double lk = Double.NEGATIVE_INFINITY;
        for (int i = n - 1; i >= 0; i--) {
            int k = cellClamped(pa.x[i], pa.y[i]);
            if (lake[k]) lk = Math.max(lk, lakeY[k]);
            wl[i] = Math.max(Math.max(wl[i], lk), sea);
        }
        pa.wl = wl;

        double[] fp = new double[n];
        double[] ws = new double[n];
        double reach = 0;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double lowish = 1 - Grid.bilinear(wH, W, H, pa.x[i], pa.y[i]);
            fp[i] = (6 + 2.5 * pa.hw[i] + p.floodplain * Math.sqrt(pa.dq)) * (0.25 + 0.75 * lowish);
            ws[i] = p.wallLow + (p.wallHigh - p.wallLow) * (1 - lowish);
            reach = Math.max(reach, fp[i] + ws[i] * (fp[i] + 30));
            minX = Math.min(minX, pa.x[i]);
            maxX = Math.max(maxX, pa.x[i]);
            minY = Math.min(minY, pa.y[i]);
            maxY = Math.max(maxY, pa.y[i]);
        }
        int r = (int) Math.ceil(reach / dx) + 4;
        int x0 = Math.max(0, (int) Math.floor(minX) - r), y0 = Math.max(0, (int) Math.floor(minY) - r);
        int x1 = Math.min(W, (int) Math.ceil(maxX) + r + 1), y1 = Math.min(H, (int) Math.ceil(maxY) + r + 1);
        if (x1 <= x0 || y1 <= y0) return;
        int ww = x1 - x0, wh = y1 - y0, wn = ww * wh;

        // rasterise the samples into the window (lowest water wins)
        int[] sampleAt = new int[wn];
        java.util.Arrays.fill(sampleAt, -1);
        for (int i = 0; i < n; i++) {
            int ix = (int) Math.round(pa.x[i]) - x0, iy = (int) Math.round(pa.y[i]) - y0;
            if (ix < 0 || iy < 0 || ix >= ww || iy >= wh) continue;
            int k = iy * ww + ix;
            if (sampleAt[k] < 0 || wl[i] < wl[sampleAt[k]]) sampleAt[k] = i;
        }
        boolean[] hit = new boolean[wn];
        boolean any = false;
        for (int k = 0; k < wn; k++) {
            hit[k] = sampleAt[k] >= 0;
            any |= hit[k];
        }
        if (!any) return;
        int[] near = new int[wn];
        float[] dist = Grid.edt(hit, ww, wh, near);
        for (int j = 0; j < wh; j++) {
            for (int i = 0; i < ww; i++) {
                int wk = j * ww + i;
                int k = (j + y0) * W + i + x0;
                if (!land[k]) continue;
                int s = sampleAt[near[wk]];
                double d = dist[wk] * dx;
                double hw = pa.hw[s];
                double h = hgt[k];
                double fl = wl[s] + 1.0 + fpNoise[k];
                // walls: the existing ground is eased down, so its texture survives
                if (h > fl) {
                    double t = Grid.smoothstep(fp[s], fp[s] + ws[s] * (fp[s] + 30), d);
                    h = Math.min(h, fl + (h - fl) * Math.pow(t, 1.5));
                }
                if (d > hw + 0.5 * dx && d <= hw + 3 * dx) h = Math.max(h, wl[s] + 1);   // no floating water
                if (d <= hw + 3 * dx) riverCore[k] = true;
                if (carveChannels && d <= hw + 0.5 * dx) {
                    double u = Grid.clamp(d / Math.max(hw, 1), 0, 1);
                    h = Math.min(h, wl[s] - 1 - 2.5 * (1 - u * u));
                    water[k] = Float.isNaN(water[k]) ? (float) wl[s] : Math.min(water[k], (float) wl[s]);
                }
                hgt[k] = (float) h;
            }
        }
    }

    /**
     * Grade roads into the terrain: a profile with both cut and fill (the
     * average of the cut-only and fill-only grade envelopes), deviation-capped,
     * applied as a height shift that fades out across a corridor widening with
     * the cut or fill depth -- a notch or a gentle embankment rather than a
     * trench. Only the profile along the road changes; the cross-slope is kept
     * (the chunk generator flattens the road core itself). Water crossings and
     * river banks are left alone (the chunk generator bridges them).
     */
    private void gradeRoads(float[] hgt, boolean[] land, boolean[] lake, boolean[] riverCore) {
        float[] bestD = Grid.fill(N, Float.POSITIVE_INFINITY);
        float[] bestDev = new float[N];
        float[] bestC = new float[N];
        float[] bestCore = new float[N];
        int[] touched = new int[N];
        double step = 0.5;                               // cells between profile samples
        double stepBlocks = step * dx;
        for (TerrainInput.Route route : in.routes) {
            if (route.x == null || route.x.length < 2) continue;
            double[][] xy = resample(route.x, route.y, step);
            double[] x = xy[0], y = xy[1];
            int n = x.length;
            if (n < 4) continue;
            double[] g0 = new double[n];
            boolean[] fixed = new boolean[n];
            int free = 0;
            for (int i = 0; i < n; i++) {
                int k = cell(x[i], y[i]);
                g0[i] = Grid.bilinear(hgt, W, H, x[i], y[i]);
                fixed[i] = k < 0 || !land[k] || lake[k] || riverCore[k] || g0[i] <= sea + 1;
                if (!fixed[i]) free++;
            }
            if (free < 4) continue;

            double[] gr = gaussian1d(g0, p.roadSmoothing / stepBlocks);
            gr = balancedGrade(gr, p.roadGrade * stepBlocks);
            for (int i = 0; i < n; i++) gr[i] = Grid.clamp(gr[i], g0[i] - p.roadMaxCut, g0[i] + p.roadMaxFill);
            gr = balancedGrade(gr, p.roadMaxGrade * stepBlocks);
            // the steeper pass can pull far from the ground on long climbs: cap it again
            for (int i = 0; i < n; i++) gr[i] = Grid.clamp(gr[i], g0[i] - p.roadMaxCut, g0[i] + p.roadMaxFill);
            // fade the change out toward crossings, which keep their ground
            double[] taper = new double[n];
            double lastFixed = -1e9;
            for (int i = 0; i < n; i++) {
                if (fixed[i]) lastFixed = i;
                taper[i] = i - lastFixed;
            }
            lastFixed = 1e9;
            for (int i = n - 1; i >= 0; i--) {
                if (fixed[i]) lastFixed = i;
                taper[i] = Grid.smoothstep(0, 20, Math.min(taper[i], lastFixed - i));
            }

            double core = route.road ? 6.4 : 4.4;
            int nt = 0;
            for (int i = 0; i + 1 < n; i++) {
                double dev0 = (gr[i] - g0[i]) * taper[i];
                double reach = core + 4 + Math.abs(dev0) * p.roadSideRatio;
                int rc = (int) Math.ceil(reach / dx) + 1;
                double ax = x[i], ay = y[i], bx = x[i + 1], by = y[i + 1];
                double vx = bx - ax, vy = by - ay, len2 = vx * vx + vy * vy;
                int lx0 = Math.max(0, (int) Math.floor(Math.min(ax, bx)) - rc);
                int lx1 = Math.min(W - 1, (int) Math.ceil(Math.max(ax, bx)) + rc);
                int ly0 = Math.max(0, (int) Math.floor(Math.min(ay, by)) - rc);
                int ly1 = Math.min(H - 1, (int) Math.ceil(Math.max(ay, by)) + rc);
                for (int cy = ly0; cy <= ly1; cy++) {
                    for (int cx = lx0; cx <= lx1; cx++) {
                        double t = len2 <= 0 ? 0 : Grid.clamp(((cx - ax) * vx + (cy - ay) * vy) / len2, 0, 1);
                        double d = Math.hypot(cx - (ax + t * vx), cy - (ay + t * vy)) * dx;
                        if (d > reach) continue;
                        int k = cy * W + cx;
                        if (d < bestD[k]) {
                            if (bestD[k] == Float.POSITIVE_INFINITY) touched[nt++] = k;
                            bestD[k] = (float) d;
                            bestDev[k] = (float) dev0;
                            bestC[k] = (float) (4 + Math.abs(dev0) * p.roadSideRatio);
                            bestCore[k] = (float) core;
                        }
                    }
                }
            }
            for (int q = 0; q < nt; q++) {
                int k = touched[q];
                if (land[k] && !lake[k] && !riverCore[k]) {
                    double t = Grid.smoothstep(bestCore[k], bestCore[k] + bestC[k], bestD[k]);
                    hgt[k] += (float) (bestDev[k] * (1 - t));
                }
                bestD[k] = Float.POSITIVE_INFINITY;
            }
        }
    }

    /** Average of the cut-only and fill-only envelopes with slope {@code lim}: cut and fill in balance. */
    private static double[] balancedGrade(double[] g, double lim) {
        int n = g.length;
        double[] up = g.clone(), lo = g.clone();
        for (int i = 1; i < n; i++) {
            up[i] = Math.min(up[i], up[i - 1] + lim);
            lo[i] = Math.max(lo[i], lo[i - 1] - lim);
        }
        for (int i = n - 2; i >= 0; i--) {
            up[i] = Math.min(up[i], up[i + 1] + lim);
            lo[i] = Math.max(lo[i], lo[i + 1] - lim);
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = 0.5 * (up[i] + lo[i]);
        return out;
    }

    // ---- small helpers ------------------------------------------------------------------------

    private int cell(double x, double y) {
        int i = (int) Math.round(x), j = (int) Math.round(y);
        return (i < 0 || j < 0 || i >= W || j >= H) ? -1 : j * W + i;
    }

    private int cellClamped(double x, double y) {
        int i = (int) Grid.clamp(Math.round(x), 0, W - 1), j = (int) Grid.clamp(Math.round(y), 0, H - 1);
        return j * W + i;
    }

    /** Resample an FMG-pixel polyline into cell coordinates at a fixed arc-length step. */
    private double[][] resample(double[] fx, double[] fy, double step) {
        List<double[]> pts = new ArrayList<>();
        double px = fx[0] * S - 0.5, py = fy[0] * S - 0.5;
        pts.add(new double[]{px, py});
        double carry = 0;
        for (int i = 1; i < fx.length; i++) {
            double qx = fx[i] * S - 0.5, qy = fy[i] * S - 0.5;
            double seg = Math.hypot(qx - px, qy - py);
            double pos = step - carry;
            while (pos <= seg) {
                double t = pos / seg;
                pts.add(new double[]{px + (qx - px) * t, py + (qy - py) * t});
                pos += step;
            }
            carry = seg - (pos - step);
            px = qx;
            py = qy;
        }
        double[][] out = new double[2][pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            out[0][i] = pts.get(i)[0];
            out[1][i] = pts.get(i)[1];
        }
        return out;
    }

    private static double[][] catmullRom(double[] x, double[] y, double step) {
        int m = x.length;
        List<double[]> pts = new ArrayList<>();
        for (int s = 0; s < m - 1; s++) {
            int i0 = Math.max(0, s - 1), i3 = Math.min(m - 1, s + 2);
            double p0x = x[i0], p0y = y[i0], p1x = x[s], p1y = y[s];
            double p2x = x[s + 1], p2y = y[s + 1], p3x = x[i3], p3y = y[i3];
            int nseg = Math.max(1, (int) Math.ceil(Math.hypot(p2x - p1x, p2y - p1y) / step));
            for (int k = 0; k < nseg; k++) {
                double t = k / (double) nseg, t2 = t * t, t3 = t2 * t;
                pts.add(new double[]{
                        0.5 * (2 * p1x + (-p0x + p2x) * t + (2 * p0x - 5 * p1x + 4 * p2x - p3x) * t2
                                + (-p0x + 3 * p1x - 3 * p2x + p3x) * t3),
                        0.5 * (2 * p1y + (-p0y + p2y) * t + (2 * p0y - 5 * p1y + 4 * p2y - p3y) * t2
                                + (-p0y + 3 * p1y - 3 * p2y + p3y) * t3)});
            }
        }
        pts.add(new double[]{x[m - 1], y[m - 1]});
        double[][] out = new double[2][pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            out[0][i] = pts.get(i)[0];
            out[1][i] = pts.get(i)[1];
        }
        return out;
    }

    /** numpy.gradient: central differences, one-sided at the ends. */
    private static double gradient(double[] a, int i) {
        int n = a.length;
        if (n < 2) return 0;
        if (i == 0) return a[1] - a[0];
        if (i == n - 1) return a[n - 1] - a[n - 2];
        return (a[i + 1] - a[i - 1]) * 0.5;
    }

    /** Box filter with edge padding, {@code passes} times. */
    private static double[] smooth1d(double[] a, int r, int passes) {
        int n = a.length;
        if (r <= 0 || n < 3) return a.clone();
        double[] cur = a.clone(), tmp = new double[n];
        for (int ps = 0; ps < passes; ps++) {
            for (int i = 0; i < n; i++) {
                double s = 0;
                for (int o = -r; o <= r; o++) s += cur[Math.min(n - 1, Math.max(0, i + o))];
                tmp[i] = s / (2 * r + 1);
            }
            double[] sw = cur;
            cur = tmp;
            tmp = sw;
        }
        return cur;
    }

    private static double[] gaussian1d(double[] a, double sigma) {
        int n = a.length;
        if (sigma <= 0.5 || n < 3) return a.clone();
        int r = (int) Math.ceil(sigma * 3);
        double[] k = new double[2 * r + 1];
        double sum = 0;
        for (int i = -r; i <= r; i++) {
            k[i + r] = Math.exp(-0.5 * i * i / (sigma * sigma));
            sum += k[i + r];
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int o = -r; o <= r; o++) s += k[o + r] * a[Math.min(n - 1, Math.max(0, i + o))];
            out[i] = s / sum;
        }
        return out;
    }

    /** Slope magnitude (height per block) with numpy.gradient semantics. */
    private float[] slope(float[] a) {
        float[] out = new float[N];
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++) {
                int k = j * W + i;
                double gx = i == 0 ? a[k + 1] - a[k] : (i == W - 1 ? a[k] - a[k - 1] : (a[k + 1] - a[k - 1]) * 0.5);
                double gy = j == 0 ? a[k + W] - a[k] : (j == H - 1 ? a[k] - a[k - W] : (a[k + W] - a[k - W]) * 0.5);
                out[k] = (float) (Math.hypot(gx, gy) / dx);
            }
        }
        return out;
    }

    private static float[] scale(float[] a, double s) {
        for (int i = 0; i < a.length; i++) a[i] *= (float) s;
        return a;
    }

    private static float[] toFloat(double[] a) {
        float[] out = new float[a.length];
        for (int i = 0; i < a.length; i++) out[i] = (float) a[i];
        return out;
    }

    private static double sq(double v) {
        return v * v;
    }
}
