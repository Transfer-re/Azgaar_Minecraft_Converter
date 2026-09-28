package com.example.terrain;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.example.heightmap.HeightPostProcess;

/**
 * The baked terrain: a height raster (world Y) plus what the chunk generator
 * needs at block resolution -- detail masks, lake water levels and the river
 * paths with their water profile. Sampled per block column by
 * {@link #heightAt}; everything here is immutable after the bake.
 */
public final class TerrainRaster {

    private static final int MAGIC = 0x464D5452;   // "FMTR"
    private static final int VERSION = 1;
    public static final short NO_WATER = Short.MIN_VALUE;

    public final int w;
    public final int h;
    /** World block coordinate of cell (0, 0)'s corner. */
    public final double originX;
    public final double originZ;
    /** Blocks per cell. */
    public final double dx;
    public final int seaLevel;

    public final float[] height;
    /** 0..255: strength of the fine per-block detail (gentle ground away from water). */
    public final byte[] fineMask;
    /** 0..255: strength of the crag detail (steep ground). */
    public final byte[] cragMask;
    /** Lake water surface Y per cell (dilated past the shore), or {@link #NO_WATER}. */
    public final short[] lakeWaterY;
    public final List<River> rivers = new ArrayList<>();

    public double fineAmp = 1.4;
    public double cragAmp = 1.0;
    public long detailSeed = 1;

    /** A baked river: block-coordinate path from source to mouth with its water profile. */
    public static final class River {
        public int id;
        public int parent;
        public float[] x;
        public float[] z;
        /** Continuous water surface Y, never rising downstream. */
        public float[] waterY;
        public float[] halfWidth;
    }

    public TerrainRaster(int w, int h, double originX, double originZ, double dx, int seaLevel) {
        this.w = w;
        this.h = h;
        this.originX = originX;
        this.originZ = originZ;
        this.dx = dx;
        this.seaLevel = seaLevel;
        this.height = new float[w * h];
        this.fineMask = new byte[w * h];
        this.cragMask = new byte[w * h];
        this.lakeWaterY = new short[w * h];
        java.util.Arrays.fill(lakeWaterY, NO_WATER);
    }

    // ---- sampling -------------------------------------------------------

    /** Fractional cell coordinate (cell centres on integers) of a block column's centre. */
    public double cellX(double worldX) {
        return (worldX + 0.5 - originX) / dx - 0.5;
    }

    public double cellZ(double worldZ) {
        return (worldZ + 0.5 - originZ) / dx - 0.5;
    }

    public boolean contains(double worldX, double worldZ) {
        double cx = cellX(worldX), cz = cellZ(worldZ);
        return cx >= -0.5 && cz >= -0.5 && cx <= w - 0.5 && cz <= h - 0.5;
    }

    /** Smooth raster height (no per-block detail). */
    public double baseHeightAt(double worldX, double worldZ) {
        return cubic(height, cellX(worldX), cellZ(worldZ));
    }

    /** Terrain height of a block column: smooth raster + per-block detail. */
    public double heightAt(int worldX, int worldZ) {
        double cx = cellX(worldX), cz = cellZ(worldZ);
        double y = cubic(height, cx, cz);
        double fine = mask(fineMask, cx, cz);
        double crag = mask(cragMask, cx, cz);
        if (fine > 0 && fineAmp > 0) {
            double n = (HeightPostProcess.valueNoise(worldX / 14.0, worldZ / 14.0, detailSeed) - 0.5) * 2.0
                    + (HeightPostProcess.valueNoise(worldX / 5.0, worldZ / 5.0, detailSeed + 1) - 0.5) * 0.8;
            y += n * fineAmp * fine;
        }
        if (crag > 0 && cragAmp > 0) {
            double c1 = 1 - Math.abs(HeightPostProcess.valueNoise(worldX / 23.0, worldZ / 23.0, detailSeed + 2) * 2 - 1);
            double c2 = 1 - Math.abs(HeightPostProcess.valueNoise(worldX / 9.0, worldZ / 9.0, detailSeed + 3) * 2 - 1);
            y += crag * cragAmp * (c1 * c1 * 7 + c2 * c2 * 3 - 3.4);
        }
        return y;
    }

    /** Lake surface Y for a block column, or {@link #NO_WATER}. */
    public int lakeWaterAt(int worldX, int worldZ) {
        int i = (int) Math.round(cellX(worldX));
        int j = (int) Math.round(cellZ(worldZ));
        if (i < 0 || j < 0 || i >= w || j >= h) return NO_WATER;
        return lakeWaterY[j * w + i];
    }

    private double mask(byte[] m, double x, double y) {
        x = Grid.clamp(x, 0, w - 1.0001);
        y = Grid.clamp(y, 0, h - 1.0001);
        int i = (int) x, j = (int) y;
        double fx = x - i, fy = y - j;
        int k = j * w + i;
        double v = (m[k] & 0xFF) * (1 - fx) * (1 - fy) + (m[k + 1] & 0xFF) * fx * (1 - fy)
                + (m[k + w] & 0xFF) * (1 - fx) * fy + (m[k + w + 1] & 0xFF) * fx * fy;
        return v / 255.0;
    }

    /**
     * Catmull-Rom bicubic, clamped to the range of the four nearest cells so it
     * never overshoots at shores and cliff edges.
     */
    private double cubic(float[] a, double x, double y) {
        x = Grid.clamp(x, 0, w - 1.0001);
        y = Grid.clamp(y, 0, h - 1.0001);
        int i = (int) x, j = (int) y;
        double tx = x - i, ty = y - j;
        double s = 0;
        for (int m = -1; m <= 2; m++) {
            int yy = Math.min(h - 1, Math.max(0, j + m));
            double r = 0;
            for (int l = -1; l <= 2; l++) {
                int xx = Math.min(w - 1, Math.max(0, i + l));
                r += cr(tx, l) * a[yy * w + xx];
            }
            s += cr(ty, m) * r;
        }
        int k = j * w + i;
        double lo = Math.min(Math.min(a[k], a[k + 1]), Math.min(a[k + w], a[k + w + 1]));
        double hi = Math.max(Math.max(a[k], a[k + 1]), Math.max(a[k + w], a[k + w + 1]));
        return Grid.clamp(s, lo, hi);
    }

    private static double cr(double t, int tap) {
        switch (tap) {
            case -1: return ((-0.5 * t + 1.0) * t - 0.5) * t;
            case 0: return (1.5 * t - 2.5) * t * t + 1.0;
            case 1: return ((-1.5 * t + 2.0) * t + 0.5) * t;
            default: return (0.5 * t - 0.5) * t * t;
        }
    }

    public float maxHeight() {
        return Grid.max(height);
    }

    // ---- persistence ----------------------------------------------------

    public void writeTo(DataOutputStream out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(w);
        out.writeInt(h);
        out.writeDouble(originX);
        out.writeDouble(originZ);
        out.writeDouble(dx);
        out.writeInt(seaLevel);
        out.writeDouble(fineAmp);
        out.writeDouble(cragAmp);
        out.writeLong(detailSeed);
        for (float v : height) out.writeFloat(v);
        out.write(fineMask);
        out.write(cragMask);
        for (short v : lakeWaterY) out.writeShort(v);
        out.writeInt(rivers.size());
        for (River r : rivers) {
            out.writeInt(r.id);
            out.writeInt(r.parent);
            out.writeInt(r.x.length);
            for (int i = 0; i < r.x.length; i++) {
                out.writeFloat(r.x[i]);
                out.writeFloat(r.z[i]);
                out.writeFloat(r.waterY[i]);
                out.writeFloat(r.halfWidth[i]);
            }
        }
    }

    public static TerrainRaster readFrom(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("not a terrain raster");
        int version = in.readInt();
        if (version != VERSION) throw new IOException("unsupported terrain raster version " + version);
        int w = in.readInt();
        int h = in.readInt();
        TerrainRaster t = new TerrainRaster(w, h, in.readDouble(), in.readDouble(), in.readDouble(), in.readInt());
        t.fineAmp = in.readDouble();
        t.cragAmp = in.readDouble();
        t.detailSeed = in.readLong();
        for (int i = 0; i < t.height.length; i++) t.height[i] = in.readFloat();
        in.readFully(t.fineMask);
        in.readFully(t.cragMask);
        for (int i = 0; i < t.lakeWaterY.length; i++) t.lakeWaterY[i] = in.readShort();
        int nr = in.readInt();
        for (int r = 0; r < nr; r++) {
            River rv = new River();
            rv.id = in.readInt();
            rv.parent = in.readInt();
            int n = in.readInt();
            rv.x = new float[n];
            rv.z = new float[n];
            rv.waterY = new float[n];
            rv.halfWidth = new float[n];
            for (int i = 0; i < n; i++) {
                rv.x[i] = in.readFloat();
                rv.z[i] = in.readFloat();
                rv.waterY[i] = in.readFloat();
                rv.halfWidth[i] = in.readFloat();
            }
            t.rivers.add(rv);
        }
        return t;
    }
}
