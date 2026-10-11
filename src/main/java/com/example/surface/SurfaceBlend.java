package com.example.surface;

import java.util.Arrays;

/**
 * Soft borders between ground palettes.
 *
 * <p>A plain lookup gives every column exactly one biome, so where two biomes
 * meet -- glacier ice against forest floor -- the ground switches on a line,
 * however wavy the border is drawn. Here each column instead counts the biomes
 * within {@code radius} blocks of it (every sample weighted alike, which gives a
 * longer, more even fade than a falloff would) and draws its palette in that
 * proportion, in small clumps: at the border it is roughly half and half, and
 * the neighbour's share thins out to nothing over the radius. So snow
 * breaks up into patches on its way into the forest, and forest floor reaches
 * into the edge of the ice, instead of either stopping dead.
 *
 * <p>Biomes are looked up on a 4-block grid, not per column, which makes this
 * cheaper than one lookup per column; a column whose whole neighbourhood is one
 * biome skips the weighing. Minecraft-free: the game and the harness share it.
 */
public final class SurfaceBlend {

    /** Biome (or palette) id at a block position. */
    public interface Lookup {
        String at(int x, int z);
    }

    private static final int STEP = 4;          // grid spacing, blocks
    private static final double CLUMP = 3.5;    // patch size of the mix, blocks

    private SurfaceBlend() {
    }

    /**
     * Ids for every column of a {@code w x h} area at ({@code x0}, {@code z0}),
     * indexed {@code (x - x0) * h + (z - z0)}.
     */
    public static String[] choose(Lookup lookup, int x0, int z0, int w, int h, double radius, int seed) {
        String[] out = new String[w * h];
        if (radius <= 0) {
            for (int i = 0; i < w; i++) {
                for (int j = 0; j < h; j++) {
                    out[i * h + j] = lookup.at(x0 + i, z0 + j);
                }
            }
            return out;
        }

        int reach = (int) Math.ceil(radius / STEP);
        int gx0 = Math.floorDiv(x0, STEP) - reach;
        int gz0 = Math.floorDiv(z0, STEP) - reach;
        int gw = Math.floorDiv(x0 + w - 1, STEP) + reach + 1 - gx0 + 1;
        int gh = Math.floorDiv(z0 + h - 1, STEP) + reach + 1 - gz0 + 1;
        String[] grid = new String[gw * gh];
        for (int gi = 0; gi < gw; gi++) {
            for (int gj = 0; gj < gh; gj++) {
                grid[gi * gh + gj] = lookup.at((gx0 + gi) * STEP, (gz0 + gj) * STEP);
            }
        }

        String[] ids = new String[8];
        double[] weights = new double[8];
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int x = x0 + i, z = z0 + j;
                int ci = Math.floorDiv(x, STEP) - gx0, cj = Math.floorDiv(z, STEP) - gz0;
                int n = 0;
                boolean uniform = true;
                String first = grid[ci * gh + cj];
                for (int di = -reach; di <= reach + 1 && uniform; di++) {
                    for (int dj = -reach; dj <= reach + 1; dj++) {
                        String g = grid[(ci + di) * gh + (cj + dj)];
                        if (g == null ? first != null : !g.equals(first)) {
                            uniform = false;
                            break;
                        }
                    }
                }
                if (uniform) {
                    out[i * h + j] = first;
                    continue;
                }
                Arrays.fill(weights, 0);
                for (int di = -reach; di <= reach + 1; di++) {
                    for (int dj = -reach; dj <= reach + 1; dj++) {
                        int gx = (ci + di + gx0) * STEP, gz = (cj + dj + gz0) * STEP;
                        double d2 = (double) (gx - x) * (gx - x) + (double) (gz - z) * (gz - z);
                        if (d2 > radius * radius) continue;
                        double wgt = 1.0;
                        String g = grid[(ci + di) * gh + (cj + dj)];
                        int k = 0;
                        while (k < n && !same(ids[k], g)) k++;
                        if (k == n) {
                            if (n == ids.length) continue;   // more than 8 biomes in reach: ignore the rest
                            ids[n++] = g;
                        }
                        weights[k] += wgt;
                    }
                }
                sortById(ids, weights, n);       // a stable order, so clumps agree between chunks
                double total = 0;
                for (int k = 0; k < n; k++) total += weights[k];
                int pick = total <= 0 ? 0
                        : SurfaceNoise.cellPick(x, z, CLUMP, seed + 0x51E7, Arrays.copyOf(weights, n), total);
                out[i * h + j] = ids[pick];
            }
        }
        return out;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static void sortById(String[] ids, double[] weights, int n) {
        for (int a = 1; a < n; a++) {
            String id = ids[a];
            double w = weights[a];
            int b = a - 1;
            while (b >= 0 && compare(ids[b], id) > 0) {
                ids[b + 1] = ids[b];
                weights[b + 1] = weights[b];
                b--;
            }
            ids[b + 1] = id;
            weights[b + 1] = w;
        }
    }

    private static int compare(String a, String b) {
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }
}
