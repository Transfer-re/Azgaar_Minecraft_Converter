package com.example.terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the terrain bake reads from an FMG map, already rasterised to
 * FMG-pixel resolution ({@code a[y * fmgW + x]}) or given as polylines in FMG
 * pixel coordinates. Kept free of the FMG/Minecraft classes so the bake can run
 * in a plain JVM (see {@link TerrainCli}).
 */
public final class TerrainInput {

    public final int fmgW;
    public final int fmgH;
    /** FMG height (0..100, sea level 20) per FMG pixel. */
    public final float[] height;
    /** FMG biome id per FMG pixel. */
    public final byte[] biome;
    /** FMG lake surface height (FMG units) per FMG pixel, 0 where not a lake. */
    public final float[] lakeLevel;

    /** Blocks per FMG pixel. */
    public double scale;
    /** World block coordinate of the FMG map's (0, 0) corner. */
    public double originX;
    public double originZ;
    public int seaLevel = 63;
    public int minY = -64;
    /** Exclusive build limit the terrain has to fit under. */
    public int maxY = 400;
    public long seed = 1;

    public final List<River> rivers = new ArrayList<>();
    public final List<Route> routes = new ArrayList<>();

    public TerrainInput(int fmgW, int fmgH) {
        this.fmgW = fmgW;
        this.fmgH = fmgH;
        this.height = new float[fmgW * fmgH];
        this.biome = new byte[fmgW * fmgH];
        this.lakeLevel = new float[fmgW * fmgH];
    }

    /**
     * Fill the FMG-pixel rasters from cell data: heights from the nearest grid
     * cell (grid cells also cover the open ocean), biome and lake level from the
     * nearest pack cell (pack cells follow the coast more closely).
     *
     * @param gridXY     grid cell points, interleaved x, y (FMG pixels)
     * @param gridH      grid cell heights (FMG units)
     * @param packXY     pack cell centres, interleaved x, y
     * @param packBiome  pack cell biome ids
     * @param packLake   pack cell lake surface height (FMG units), 0 if the cell is not a lake
     */
    public void rasterise(double[] gridXY, float[] gridH, double[] packXY, int[] packBiome, float[] packLake) {
        Nearest grid = new Nearest(gridXY, fmgW, fmgH);
        Nearest pack = new Nearest(packXY, fmgW, fmgH);
        java.util.stream.IntStream.range(0, fmgH).parallel().forEach(y -> {
            for (int x = 0; x < fmgW; x++) {
                int k = y * fmgW + x;
                int g = grid.find(x + 0.5, y + 0.5);
                height[k] = g < 0 ? 0f : gridH[g];
                int c = pack.find(x + 0.5, y + 0.5);
                if (c >= 0) {
                    biome[k] = (byte) packBiome[c];
                    lakeLevel[k] = packLake[c];
                }
            }
        });
    }

    /** Nearest-point lookup on a uniform bucket grid. */
    private static final class Nearest {
        private final double[] xy;
        private final int bw, bh;
        private final double cell;
        private final int[] start, items;

        Nearest(double[] xy, int w, int h) {
            this.xy = xy;
            int n = xy.length / 2;
            cell = Math.max(1.0, Math.sqrt((double) w * h / Math.max(1, n)));
            bw = (int) Math.ceil(w / cell) + 1;
            bh = (int) Math.ceil(h / cell) + 1;
            int[] count = new int[bw * bh + 1];
            for (int i = 0; i < n; i++) count[bucket(xy[2 * i], xy[2 * i + 1]) + 1]++;
            for (int b = 0; b < bw * bh; b++) count[b + 1] += count[b];
            start = count.clone();
            items = new int[n];
            int[] fill = count.clone();
            for (int i = 0; i < n; i++) items[fill[bucket(xy[2 * i], xy[2 * i + 1])]++] = i;
        }

        private int bucket(double x, double y) {
            int bx = (int) Math.max(0, Math.min(bw - 1, Math.floor(x / cell)));
            int by = (int) Math.max(0, Math.min(bh - 1, Math.floor(y / cell)));
            return by * bw + bx;
        }

        int find(double x, double y) {
            int bx = (int) Math.max(0, Math.min(bw - 1, Math.floor(x / cell)));
            int by = (int) Math.max(0, Math.min(bh - 1, Math.floor(y / cell)));
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int ring = 0; ring <= Math.max(bw, bh); ring++) {
                if (best >= 0 && (ring - 1) * cell > Math.sqrt(bd)) break;
                for (int j = by - ring; j <= by + ring; j++) {
                    if (j < 0 || j >= bh) continue;
                    for (int i = bx - ring; i <= bx + ring; i++) {
                        if (i < 0 || i >= bw) continue;
                        if (Math.max(Math.abs(i - bx), Math.abs(j - by)) != ring) continue;
                        int b = j * bw + i;
                        for (int q = start[b]; q < start[b + 1]; q++) {
                            int it = items[q];
                            double ddx = xy[2 * it] - x, ddy = xy[2 * it + 1] - y;
                            double d = ddx * ddx + ddy * ddy;
                            if (d < bd) {
                                bd = d;
                                best = it;
                            }
                        }
                    }
                }
            }
            return best;
        }
    }

    /** An FMG river: cell-centre polyline from source to mouth (FMG pixels). */
    public static final class River {
        public int id;
        public int parent;
        public double discharge;
        public double widthKm;
        public double sourceWidthKm;
        public double[] x;
        public double[] y;
    }

    /** A road or trail, already spline-sampled (FMG pixels) exactly as the chunk generator samples it. */
    public static final class Route {
        public int id;
        public boolean road;
        public double[] x;
        public double[] y;
    }
}
