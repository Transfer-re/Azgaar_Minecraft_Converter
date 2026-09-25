package com.example.heightmap;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * A plain rectangular grid of terrain heights in world-Y units, 1 cell = 1
 * block, plus the world origin and vertical limits it was sampled with.
 *
 * This is deliberately free of any Minecraft dependency: the whole point is to
 * dump the generator's raw heightfield to a file and then iterate on
 * post-processing (rivers, erosion, detail noise) in a standalone harness
 * ({@link HeightPostProcess}) without spinning up the game.
 *
 * On-disk format ({@code .fmgh}, big-endian):
 * <pre>
 *   int32   magic     0x464D4748  ("FMGH")
 *   int32   version   1
 *   int32   originX               world X of column x=0
 *   int32   originZ               world Z of row    z=0
 *   int32   width                 number of columns (X)
 *   int32   height                number of rows    (Z)
 *   int32   seaLevel
 *   int32   worldMinY
 *   int32   worldMaxY
 *   float32 * (width*height)      row-major, z outer: value(x,z) = data[z*width + x]
 * </pre>
 */
public final class HeightField {

    public static final int MAGIC = 0x464D4748;
    public static final int VERSION = 1;

    public final int originX;
    public final int originZ;
    public final int width;   // columns, X
    public final int height;  // rows, Z
    public int seaLevel;
    public int worldMinY;
    public int worldMaxY;

    /**
     * World blocks per grid cell. 1 for a {@code .fmgh} dump (which is always
     * block-resolution); larger for a downsampled in-world erosion bake. Not
     * serialised -- {@code read}/{@code write} always round-trip cellSize 1.
     */
    public int cellSize = 1;

    /** Row-major, z outer: {@code data[z * width + x]}. */
    public final float[] data;

    public HeightField(int originX, int originZ, int width, int height,
                       int seaLevel, int worldMinY, int worldMaxY) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width/height must be positive");
        }
        this.originX = originX;
        this.originZ = originZ;
        this.width = width;
        this.height = height;
        this.seaLevel = seaLevel;
        this.worldMinY = worldMinY;
        this.worldMaxY = worldMaxY;
        this.data = new float[width * height];
    }

    // ---- accessors --------------------------------------------------

    public float get(int x, int z) {
        return data[z * width + x];
    }

    public void set(int x, int z, float v) {
        data[z * width + x] = v;
    }

    /** Clamped sample: coordinates outside the grid snap to the nearest edge cell. */
    public float getClamped(int x, int z) {
        int cx = x < 0 ? 0 : (x >= width ? width - 1 : x);
        int cz = z < 0 ? 0 : (z >= height ? height - 1 : z);
        return data[cz * width + cx];
    }

    /** Bilinear sample in grid space (x,z may be fractional). */
    public float sampleBilinear(double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double tx = x - x0;
        double tz = z - z0;
        float h00 = getClamped(x0, z0);
        float h10 = getClamped(x0 + 1, z0);
        float h01 = getClamped(x0, z0 + 1);
        float h11 = getClamped(x0 + 1, z0 + 1);
        float a = (float) (h00 + (h10 - h00) * tx);
        float b = (float) (h01 + (h11 - h01) * tx);
        return (float) (a + (b - a) * tz);
    }

    /** World X of grid column {@code x}. */
    public int worldX(int x) {
        return originX + x * cellSize;
    }

    /** World Z of grid row {@code z}. */
    public int worldZ(int z) {
        return originZ + z * cellSize;
    }

    /** Fractional grid column for a world X (inverse of {@link #worldX}). */
    public double gridX(double worldX) {
        return (worldX - originX) / (double) cellSize;
    }

    /** Fractional grid row for a world Z (inverse of {@link #worldZ}). */
    public double gridZ(double worldZ) {
        return (worldZ - originZ) / (double) cellSize;
    }

    /** Bilinear height at a world coordinate. */
    public float heightAtWorld(double worldX, double worldZ) {
        return sampleBilinear(gridX(worldX), gridZ(worldZ));
    }

    public HeightField copy() {
        HeightField c = new HeightField(originX, originZ, width, height, seaLevel, worldMinY, worldMaxY);
        c.cellSize = cellSize;
        System.arraycopy(data, 0, c.data, 0, data.length);
        return c;
    }

    public float min() {
        float m = Float.POSITIVE_INFINITY;
        for (float v : data) if (v < m) m = v;
        return m;
    }

    public float max() {
        float m = Float.NEGATIVE_INFINITY;
        for (float v : data) if (v > m) m = v;
        return m;
    }

    // ---- .fmgh IO --------------------------------------------------

    public void write(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (OutputStream os = Files.newOutputStream(file);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(os))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(originX);
            out.writeInt(originZ);
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(seaLevel);
            out.writeInt(worldMinY);
            out.writeInt(worldMaxY);
            for (float v : data) out.writeFloat(v);
        }
    }

    public static HeightField read(Path file) throws IOException {
        try (InputStream is = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(new BufferedInputStream(is))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("not a .fmgh file (bad magic 0x" + Integer.toHexString(magic) + ")");
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("unsupported .fmgh version " + version);
            }
            int ox = in.readInt();
            int oz = in.readInt();
            int w = in.readInt();
            int h = in.readInt();
            int sea = in.readInt();
            int minY = in.readInt();
            int maxY = in.readInt();
            HeightField field = new HeightField(ox, oz, w, h, sea, minY, maxY);
            for (int i = 0; i < field.data.length; i++) field.data[i] = in.readFloat();
            return field;
        }
    }

    // ---- previews (write-only; the .fmgh is the source of truth) --

    /**
     * 16-bit grayscale PNG, contrast-stretched so the field's own min maps to
     * black and its max to white -- i.e. a viewable elevation map, not a
     * (lossless but near-black) raw-height dump. The {@code .fmgh} stays the
     * source of truth for exact values; this is only for eyeballing.
     *
     * @return the {@code [min, max]} height range mapped to {@code [0, 65535]},
     *         so two images can be compared knowing each one's mapping
     */
    public float[] writeHeightPng(Path file) throws IOException {
        float lo = min();
        float hi = max();
        float span = (hi - lo);
        if (span < 1e-4f) span = 1f;

        Files.createDirectories(file.toAbsolutePath().getParent());
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY);
        var raster = img.getRaster();
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int s = Math.round((get(x, z) - lo) / span * 65535f);
                if (s < 0) s = 0;
                if (s > 0xFFFF) s = 0xFFFF;
                raster.setSample(x, z, 0, s);
            }
        }
        ImageIO.write(img, "png", file.toFile());
        return new float[]{lo, hi};
    }

    /**
     * Signed per-cell difference {@code this - baseline} as an RGB PNG:
     * blue = lowered (erosion, river carving), red = raised (deposition), grey
     * where unchanged. Scaled so the largest absolute change is full colour.
     * This is the pass-effectiveness view -- run a pass, look at the diff.
     *
     * @return the largest absolute change (blocks) mapped to full colour
     */
    public double writeDiffPng(HeightField baseline, Path file) throws IOException {
        if (baseline.width != width || baseline.height != height) {
            throw new IllegalArgumentException("baseline grid size mismatch");
        }
        double maxAbs = 1e-4;
        for (int i = 0; i < data.length; i++) {
            maxAbs = Math.max(maxAbs, Math.abs(data[i] - baseline.data[i]));
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < data.length; i++) {
            double d = (data[i] - baseline.data[i]) / maxAbs; // [-1, 1]
            int r, g, b;
            if (d >= 0) {
                int mag = (int) Math.round(d * 235);
                r = 20 + mag; g = 20 + (int) (mag * 0.25); b = 20;
            } else {
                int mag = (int) Math.round(-d * 235);
                r = 20; g = 20 + (int) (mag * 0.35); b = 20 + mag;
            }
            img.setRGB(i % width, i / width, (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b));
        }
        ImageIO.write(img, "png", file.toFile());
        return maxAbs;
    }

    /**
     * Cheap hillshade preview (fixed NW light), with water below {@code seaLevel}
     * tinted blue. Purely for eyeballing the effect of a post-processing pass.
     */
    public void writeHillshadePng(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        double lx = -0.6, ly = -0.6, lz = 0.53; // NW, slightly above
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                float hL = getClamped(x - 1, z);
                float hR = getClamped(x + 1, z);
                float hU = getClamped(x, z - 1);
                float hD = getClamped(x, z + 1);
                double nx = (hL - hR) / 2.0;
                double nz = (hU - hD) / 2.0;
                double nl = Math.sqrt(nx * nx + nz * nz + 1.0);
                double dot = (nx * lx + nz * ly + 1.0 * lz) / nl;
                double shade = Math.max(0.15, Math.min(1.0, 0.45 + 0.75 * dot));

                float hgt = get(x, z);
                int r, g, b;
                if (hgt < seaLevel) {
                    double depth = Math.max(0.0, Math.min(1.0, (seaLevel - hgt) / 24.0));
                    r = (int) (shade * (40 - 20 * depth));
                    g = (int) (shade * (90 - 30 * depth));
                    b = (int) (shade * (150 - 20 * depth));
                } else {
                    double t = Math.max(0.0, Math.min(1.0, (hgt - seaLevel) / Math.max(1.0, worldMaxY - seaLevel)));
                    r = (int) (shade * (90 + 150 * t));
                    g = (int) (shade * (120 + 110 * t));
                    b = (int) (shade * (80 + 120 * t));
                }
                img.setRGB(x, z, (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b));
            }
        }
        ImageIO.write(img, "png", file.toFile());
    }

    private static int clamp8(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
