package com.example.surface;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import com.example.terrain.TerrainRaster;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Runs the layered-surface decisions on a baked terrain outside Minecraft and
 * writes what each column would be built from, for
 * {@code tools/fmgtrees/render_surface_dump.py} to draw with the real textures.
 *
 * <pre>
 *   surface.cmd ..\refinemap\outj\EnciaNow out\encia_surface.json --center 1200 -300 --size 256
 * </pre>
 *
 * The input base is a {@code TerrainCli} bake ({@code _terrain.bin},
 * {@code _meta.json}, {@code _biome.npy}). Terrain, shore distances, slope,
 * altitude and the palette decisions are the game's own code
 * ({@link SurfaceSet}, {@link SurfaceVars}); what differs is only how a column
 * gets its biome: the game uses the profile's per-region pools, this uses the
 * same pools with regions drawn as warped cells, which is close enough to
 * review palettes on real relief. River water comes from the baked river paths.
 */
public final class SurfaceCli {

    private SurfaceCli() {
    }

    /** Azgaar's default biome indices -> conquest palettes, weighted like the profile's pools. */
    private static final String[][] POOLS = {
            {"sea_temperate:1"},                                                         // 0 Marine
            {"hot_desert:1"},                                                            // 1 Hot desert
            {"cold_steppe:1"},                                                           // 2 Cold desert
            {"savanna:1"},                                                               // 3 Savanna
            {"meadow:45", "moorland:30", "downland:25"},                                 // 4 Grassland
            {"tropical_seasonal_forest:80", "savanna:20"},                               // 5
            {"oak_woodland:26", "mixed_broadleaf_forest:20", "beech_forest:14",          // 6 Temperate deciduous
                    "autumn_broadleaf_forest:12", "birch_wood:10", "mixed_conifer_broadleaf:10",
                    "aspen_grove:8"},
            {"tropical_rainforest:1"},                                                   // 7
            {"mossy_conifer_forest:80", "old_growth_spruce:20"},                         // 8 Temperate rainforest
            {"spruce_taiga:30", "pine_taiga:20", "larch_taiga:15", "autumn_larch_taiga:10", // 9 Taiga
                    "old_growth_spruce:10", "mixed_conifer_broadleaf:5", "birch_wood:5", "dead_taiga:5"},
            {"tundra"},                                                                  // 10 (ladder)
            {"glacier:1"},                                                               // 11
            {"sphagnum_bog:40", "fen_reedbed:35", "willow_carr:25"},                     // 12 Wetland
    };

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("usage: SurfaceCli <bakeBase> <out.json> [--center X Z] [--size 256]"
                    + " [--palettes <conquest.json>] [--palette <id>] [--seed N] [--blend 16]");
            return;
        }
        String base = args[0];
        Path out = Path.of(args[1]);
        int cx = 0, cz = 0, size = 256, seed = 1;
        double blend = 16.0;                       // the conquest profile's biomeEdgeBlend
        String forced = null;
        Path palettes = Path.of("src/main/resources/fantasymapgenerator/surface/conquest.json");
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--center" -> {
                    cx = Integer.parseInt(args[++i]);
                    cz = Integer.parseInt(args[++i]);
                }
                case "--size" -> size = Integer.parseInt(args[++i]);
                case "--palettes" -> palettes = Path.of(args[++i]);
                case "--palette" -> forced = args[++i];
                case "--seed" -> seed = Integer.parseInt(args[++i]);
                case "--blend" -> blend = Double.parseDouble(args[++i]);
                default -> System.out.println("(ignoring " + args[i] + ")");
            }
        }

        TerrainRaster raster;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(Path.of(base + "_terrain.bin")))))) {
            raster = TerrainRaster.readFrom(in);
        }
        JsonObject meta = JsonParser.parseString(Files.readString(Path.of(base + "_meta.json"))).getAsJsonObject();
        double bpp = meta.get("blocksPerPixel").getAsDouble();
        double ox = meta.getAsJsonArray("originBlocks").get(0).getAsDouble();
        double oz = meta.getAsJsonArray("originBlocks").get(1).getAsDouble();
        Npy biomes = Npy.read(Path.of(base + "_biome.npy"));
        SurfaceSet set;
        try (Reader r = Files.newBufferedReader(palettes, StandardCharsets.UTF_8)) {
            set = SurfaceSet.read(r);
        }
        int sea = raster.seaLevel;
        double top = raster.maxHeight();

        // padded window: the shore search needs R past the edge, slope one more
        int pad = SurfaceVars.SHORE_RADIUS + 1;
        int n = size + 2 * pad;
        int x0 = cx - size / 2 - pad, z0 = cz - size / 2 - pad;
        double[] exact = new double[n * n];
        int[] height = new int[n * n];
        float[] water = new float[n * n];                // fresh water surface, or NaN
        java.util.Arrays.fill(water, Float.NaN);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double h = raster.heightAt(x0 + i, z0 + j);
                exact[i * n + j] = h;
                height[i * n + j] = (int) Math.round(h);
            }
        }
        stampRivers(raster, x0, z0, n, water);
        byte[] kind = new byte[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int lake = raster.lakeWaterAt(x0 + i, z0 + j);
                if (lake != TerrainRaster.NO_WATER && height[k] - 1 < lake) water[k] = lake + 1f;
                if (!Float.isNaN(water[k])) {
                    // carve a bed under fresh water the way the game's river pass does
                    int bedTop = Math.min(height[k] - 1, (int) Math.floor(water[k]) - 2);
                    height[k] = bedTop + 1;
                    exact[k] = height[k];
                    kind[k] = SurfaceVars.FRESH;
                } else if (height[k] < sea) {
                    kind[k] = SurfaceVars.SEA;
                }
            }
        }
        float[] dSea = SurfaceVars.chamfer(kind, n, SurfaceVars.SEA);
        float[] dFresh = SurfaceVars.chamfer(kind, n, SurfaceVars.FRESH);

        // palettes for the whole window, blended across borders like the game
        final int wx0 = x0 + pad, wz0 = z0 + pad, fseed = seed;
        final double fbpp = bpp, fox = ox, foz = oz;
        final TerrainRaster fr = raster;
        String[] blended = forced != null ? null : SurfaceBlend.choose(
                (bx, bz) -> paletteFor(biomes, fbpp, fox, foz, bx, bz,
                        SurfaceVars.altitude(fr.heightAt(bx, bz), sea, top), fseed),
                wx0, wz0, size, size, blend, seed);

        SurfaceSet.Vars v = new SurfaceSet.Vars();
        SurfaceSet.Choice c = new SurfaceSet.Choice();
        java.util.List<String> paletteIds = new java.util.ArrayList<>();
        StringBuilder cols = new StringBuilder();
        for (int j = pad; j < pad + size; j++) {                 // z rows
            for (int i = pad; i < pad + size; i++) {             // x columns
                int k = i * n + j;
                int x = x0 + i, z = z0 + j;
                int h = height[k];
                boolean fresh = kind[k] == SurfaceVars.FRESH;
                boolean underwater = fresh || h < sea;
                double s = SurfaceVars.clampExact(exact[k], h);
                v.slope = SurfaceVars.slope(
                        SurfaceVars.clampExact(exact[k + n], height[k + n]),
                        SurfaceVars.clampExact(exact[k - n], height[k - n]),
                        SurfaceVars.clampExact(exact[k + 1], height[k + 1]),
                        SurfaceVars.clampExact(exact[k - 1], height[k - 1]));
                v.coast = SurfaceVars.capped(dSea[k]);
                v.bank = SurfaceVars.capped(dFresh[k]);
                v.alt = SurfaceVars.altitude(s, sea, top);
                v.underwater = underwater;
                double surfaceY = fresh ? water[k] : sea;
                v.depth = underwater ? surfaceY - h : 0;

                String pid = forced != null ? forced : blended[(x - wx0) * size + (z - wz0)];
                SurfaceSet.Palette p = set.palette(pid);
                if (p == null) p = set.forBiome(null);
                int pi = paletteIds.indexOf(p.id);
                if (pi < 0) {
                    paletteIds.add(p.id);
                    pi = paletteIds.size() - 1;
                }
                set.decide(p, x, z, v, seed, c);
                int yTop, layers;
                if (underwater) {
                    yTop = h - 1;
                    layers = 8;                                   // beds stay full blocks
                } else {
                    int[] yk = SurfaceSet.layerOf(s);
                    yTop = yk[0];
                    layers = yk[1];
                }
                if (cols.length() > 0) cols.append(',');
                cols.append(String.format(Locale.ROOT, "[%.3f,%d,%d,%d,%d,%d,%s,%d,%d,%d]",
                        s, h, yTop, layers, c.material, pi,
                        underwater ? String.format(Locale.ROOT, "%.2f", surfaceY) : "-1",
                        underwater ? Math.max(1, SurfaceSet.fillDepth(p, v.slope)) : SurfaceSet.fillDepth(p, v.slope),
                        c.var, c.sub));
            }
        }

        Files.createDirectories(out.toAbsolutePath().getParent());
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("{\"origin\":[" + (x0 + pad) + "," + (z0 + pad) + "],\"size\":" + size
                    + ",\"seaLevel\":" + sea + ",\"columns\":\"s,h,yTop,layers,material,palette,water,fill,var,sub\"");
            w.write(",\"materials\":" + jsonArray(set.materialNames));
            w.write(",\"layer\":" + jsonArray(set.layerIds));
            w.write(",\"full\":" + jsonArray(set.fullIds));
            w.write(",\"skin\":[");
            for (int m = 0; m < set.skin.length; m++) w.write((m > 0 ? "," : "") + set.skin[m]);
            w.write("],\"palettes\":[");
            for (int pi = 0; pi < paletteIds.size(); pi++) {
                SurfaceSet.Palette p = set.palette(paletteIds.get(pi));
                w.write((pi > 0 ? "," : "") + String.format(Locale.ROOT,
                        "{\"id\":\"%s\",\"soil\":\"%s\",\"stone\":%d,\"grass\":%d,\"foliage\":%d,\"water\":%d}",
                        p.id, p.soil, p.stone, p.tints[0], p.tints[1], p.tints[2]));
            }
            w.write("],\"data\":[" + cols + "]}");
        }
        System.out.printf(Locale.ROOT, "wrote %s: %dx%d columns around (%d, %d), %d palettes %s%n",
                out, size, size, cx, cz, paletteIds.size(), paletteIds);
    }

    private static String paletteFor(Npy biomes, double bpp, double ox, double oz,
                                     int x, int z, double alt, int seed) {
        // warp the lookup like the biome source does, so borders wander
        double[] d = SurfaceNoise.warp(18.0, x, z);
        int px = (int) Math.floor((x + d[0] - ox) / bpp);
        int pz = (int) Math.floor((z + d[1] - oz) / bpp);
        int b = biomes.u8(Math.max(0, Math.min(biomes.rows - 1, pz)), Math.max(0, Math.min(biomes.cols - 1, px)));
        String[] pool = b >= 0 && b < POOLS.length ? POOLS[b] : POOLS[4];
        if (pool[0].equals("tundra")) {
            return alt < 40 ? "shrub_tundra" : alt < 60 ? "lichen_tundra" : "alpine_scree";
        }
        double[] w = new double[pool.length];
        double total = 0;
        for (int i = 0; i < pool.length; i++) {
            w[i] = Double.parseDouble(pool[i].substring(pool[i].indexOf(':') + 1));
            total += w[i];
        }
        // regions as large warped cells, one pool draw each
        int pick = SurfaceNoise.cellPick(x, z, 220, seed * 7919 + b * 131, w, total);
        return pool[pick].substring(0, pool[pick].indexOf(':'));
    }

    /** Fresh water surface per column from the baked river paths. */
    private static void stampRivers(TerrainRaster raster, int x0, int z0, int n, float[] water) {
        for (TerrainRaster.River r : raster.rivers) {
            for (int p = 0; p < r.x.length; p++) {
                double hw = r.halfWidth[p];
                int minI = (int) Math.floor(r.x[p] - hw) - x0, maxI = (int) Math.ceil(r.x[p] + hw) - x0;
                int minJ = (int) Math.floor(r.z[p] - hw) - z0, maxJ = (int) Math.ceil(r.z[p] + hw) - z0;
                if (maxI < 0 || maxJ < 0 || minI >= n || minJ >= n) continue;
                for (int i = Math.max(0, minI); i <= Math.min(n - 1, maxI); i++) {
                    for (int j = Math.max(0, minJ); j <= Math.min(n - 1, maxJ); j++) {
                        double dx = x0 + i - r.x[p], dz = z0 + j - r.z[p];
                        if (dx * dx + dz * dz <= hw * hw) {
                            float wy = r.waterY[p] + 1f;
                            int k = i * n + j;
                            water[k] = Float.isNaN(water[k]) ? wy : Math.min(water[k], wy);
                        }
                    }
                }
            }
        }
    }

    private static String jsonArray(String[] a) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            b.append(i > 0 ? "," : "").append('"').append(a[i]).append('"');
        }
        return b.append(']').toString();
    }

    /** Minimal reader for the bake's 2-D {@code |u1} .npy rasters. */
    static final class Npy {
        final int rows, cols;
        final byte[] data;

        private Npy(int rows, int cols, byte[] data) {
            this.rows = rows;
            this.cols = cols;
            this.data = data;
        }

        int u8(int r, int c) {
            return data[r * cols + c] & 0xFF;
        }

        static Npy read(Path file) throws IOException {
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] magic = in.readNBytes(8);
                if (magic[0] != (byte) 0x93) throw new IOException("not an .npy file: " + file);
                byte[] lenBytes = in.readNBytes(magic[6] == 1 ? 2 : 4);
                ByteBuffer lb = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN);
                int headerLen = magic[6] == 1 ? lb.getShort() & 0xFFFF : lb.getInt();
                String header = new String(in.readNBytes(headerLen), StandardCharsets.ISO_8859_1);
                if (!header.contains("|u1")) throw new IOException("expected |u1 data in " + file);
                String shape = header.substring(header.indexOf("(") + 1, header.indexOf(")"));
                String[] dims = shape.split(",");
                int rows = Integer.parseInt(dims[0].trim()), cols = Integer.parseInt(dims[1].trim());
                return new Npy(rows, cols, in.readNBytes(rows * cols));
            }
        }
    }
}
