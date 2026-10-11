package com.example.terrain;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.example.util.CatmullRomSpline;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Runs the terrain bake on an FMG JSON export outside Minecraft and writes
 * .npy rasters that refinemap/render3d.py and diag.py can preview:
 *
 * <pre>
 *   terrain.cmd "../maps/Encia Full.json" ../../refinemap/outj/Encia [--scale 12] [--max-y 768] [--seed 1]
 * </pre>
 */
public final class TerrainCli {

    private TerrainCli() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("usage: TerrainCli <map.json> <outBase> [--scale 12] [--max-y 768] [--seed N] [--no-roads]");
            return;
        }
        Path json = Path.of(args[0]);
        String outBase = args[1];
        double scale = 12;
        int maxY = 768;
        long seed = json.getFileName().toString().hashCode();
        TerrainParams params = new TerrainParams();
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--scale" -> scale = Double.parseDouble(args[++i]);
                case "--max-y" -> maxY = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--no-roads" -> params.gradeRoads = false;
                case "--river-road-clearance" -> params.riverRoadClearance = Double.parseDouble(args[++i]);
                case "--peak" -> params.peak = Double.parseDouble(args[++i]);
                case "--gamma" -> params.gamma = Double.parseDouble(args[++i]);
                case "--mtn-knee" -> params.mountainKnee = Double.parseDouble(args[++i]);
                case "--mtn-scale" -> params.mountainScale = Double.parseDouble(args[++i]);
                case "--mtn-soft" -> params.mountainSoftness = Double.parseDouble(args[++i]);
                default -> System.out.println("(ignoring " + args[i] + ")");
            }
        }

        long t0 = System.nanoTime();
        TerrainInput in = readInput(json, scale);
        in.maxY = maxY;
        in.seed = seed;
        System.out.printf(Locale.ROOT, "input %dx%d FMG px, %d rivers, %d routes (%.1f s)%n",
                in.fmgW, in.fmgH, in.rivers.size(), in.routes.size(), (System.nanoTime() - t0) / 1e9);

        long t1 = System.nanoTime();
        final String[] last = {""};
        TerrainBuilder.Preview pv = new TerrainBuilder.Preview();
        TerrainRaster r = TerrainBuilder.buildPreview(in, params, pv, (phase, f) -> {
            if (!phase.equals(last[0])) {
                System.out.printf(Locale.ROOT, "[%6.1fs] %s%n", (System.nanoTime() - t1) / 1e9, phase);
                last[0] = phase;
            }
        });
        System.out.printf(Locale.ROOT, "built %dx%d @ %.1f blocks/cell in %.1f s, max Y %.0f%n",
                r.w, r.h, r.dx, (System.nanoTime() - t1) / 1e9, r.maxHeight());

        Files.createDirectories(Path.of(outBase).toAbsolutePath().getParent());
        writeNpy(Path.of(outBase + "_height.npy"), r.height, r.h, r.w);
        writeNpy(Path.of(outBase + "_water.npy"), pv.water, r.h, r.w);
        writeNpy(Path.of(outBase + "_w.npy"), pv.weight, r.h, r.w);
        writeNpyU8(Path.of(outBase + "_biome.npy"), pv.biome, r.h, r.w);
        String meta = String.format(Locale.ROOT,
                "{\"blocksPerPixel\": %s, \"seaLevel\": %d, \"originBlocks\": [%s, %s], \"maxY\": %s}",
                r.dx, r.seaLevel, r.originX, r.originZ, r.maxHeight());
        Files.writeString(Path.of(outBase + "_meta.json"), meta, StandardCharsets.UTF_8);
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(
                new java.util.zip.GZIPOutputStream(Files.newOutputStream(Path.of(outBase + "_terrain.bin")))))) {
            r.writeTo(o);
        }
        System.out.println("wrote " + outBase + "_*");
    }

    /** Parse an FMG "Full JSON" export into bake inputs (mirrors what the mod reads). */
    public static TerrainInput readInput(Path json, double scale) throws IOException {
        JsonObject root;
        try (Reader rd = Files.newBufferedReader(json, StandardCharsets.UTF_8)) {
            root = new Gson().fromJson(rd, JsonObject.class);
        }
        JsonObject info = root.getAsJsonObject("info");
        int w = info.get("width").getAsInt(), h = info.get("height").getAsInt();
        TerrainInput in = new TerrainInput(w, h);
        in.scale = scale;
        in.originX = -(w * scale) / 2.0;
        in.originZ = -(h * scale) / 2.0;

        JsonObject grid = root.getAsJsonObject("grid");
        JsonArray gp = grid.getAsJsonArray("points");
        JsonArray gc = grid.getAsJsonArray("cells");
        double[] gridXY = new double[gp.size() * 2];
        float[] gridH = new float[gp.size()];
        for (int i = 0; i < gp.size(); i++) {
            JsonArray pt = gp.get(i).getAsJsonArray();
            gridXY[2 * i] = pt.get(0).getAsDouble();
            gridXY[2 * i + 1] = pt.get(1).getAsDouble();
            gridH[i] = gc.get(i).getAsJsonObject().get("h").getAsFloat();
        }

        JsonObject pack = root.getAsJsonObject("pack");
        java.util.Map<Integer, Float> lakes = new java.util.HashMap<>();
        for (JsonElement fe : pack.getAsJsonArray("features")) {
            if (!fe.isJsonObject()) continue;
            JsonObject f = fe.getAsJsonObject();
            if (f.has("type") && "lake".equals(f.get("type").getAsString()) && f.has("height")) {
                lakes.put(f.get("i").getAsInt(), f.get("height").getAsFloat());
            }
        }
        JsonArray pc = pack.getAsJsonArray("cells");
        double[] packXY = new double[pc.size() * 2];
        int[] biome = new int[pc.size()];
        float[] lake = new float[pc.size()];
        for (int i = 0; i < pc.size(); i++) {
            JsonObject c = pc.get(i).getAsJsonObject();
            JsonArray p = c.getAsJsonArray("p");
            packXY[2 * i] = p.get(0).getAsDouble();
            packXY[2 * i + 1] = p.get(1).getAsDouble();
            biome[i] = c.has("biome") ? c.get("biome").getAsInt() : 0;
            int fid = c.has("f") ? c.get("f").getAsInt() : 0;
            lake[i] = lakes.getOrDefault(fid, 0f);
        }
        in.rasterise(gridXY, gridH, packXY, biome, lake);

        for (JsonElement re : pack.getAsJsonArray("rivers")) {
            if (!re.isJsonObject()) continue;
            JsonObject r = re.getAsJsonObject();
            JsonArray cells = r.getAsJsonArray("cells");
            List<double[]> pts = new ArrayList<>();
            for (JsonElement ce : cells) {
                int ci = ce.getAsInt();
                if (ci >= 0 && ci < pc.size()) pts.add(new double[]{packXY[2 * ci], packXY[2 * ci + 1]});
            }
            if (pts.size() < 2) continue;
            TerrainInput.River rv = new TerrainInput.River();
            rv.id = r.get("i").getAsInt();
            rv.parent = r.has("parent") ? r.get("parent").getAsInt() : 0;
            rv.discharge = r.has("discharge") ? r.get("discharge").getAsDouble() : 0;
            rv.widthKm = r.has("width") ? r.get("width").getAsDouble() : 0.05;
            rv.sourceWidthKm = r.has("sourceWidth") ? r.get("sourceWidth").getAsDouble() : 0.02;
            rv.x = pts.stream().mapToDouble(q -> q[0]).toArray();
            rv.y = pts.stream().mapToDouble(q -> q[1]).toArray();
            in.rivers.add(rv);
        }

        for (JsonElement ro : pack.getAsJsonArray("routes")) {
            if (!ro.isJsonObject()) continue;
            JsonObject r = ro.getAsJsonObject();
            String group = r.has("group") ? r.get("group").getAsString() : "";
            if (!"roads".equals(group) && !"trails".equals(group)) continue;
            List<CatmullRomSpline.Vec2> control = new ArrayList<>();
            for (JsonElement pe : r.getAsJsonArray("points")) {
                JsonArray pt = pe.getAsJsonArray();
                control.add(new CatmullRomSpline.Vec2(pt.get(0).getAsDouble(), pt.get(1).getAsDouble()));
            }
            List<CatmullRomSpline.Vec2> s = CatmullRomSpline.sample(control, 0.10);
            if (s.size() < 2) continue;
            TerrainInput.Route rt = new TerrainInput.Route();
            rt.id = r.get("i").getAsInt();
            rt.road = "roads".equals(group);
            rt.x = s.stream().mapToDouble(CatmullRomSpline.Vec2::x).toArray();
            rt.y = s.stream().mapToDouble(CatmullRomSpline.Vec2::z).toArray();
            in.routes.add(rt);
        }
        return in;
    }

    // ---- .npy output (little-endian, C order) ----

    private static void writeNpy(Path file, float[] data, int rows, int cols) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : data) bb.putFloat(v);
        writeNpyRaw(file, "<f4", rows, cols, bb.array());
    }

    private static void writeNpyU8(Path file, byte[] data, int rows, int cols) throws IOException {
        writeNpyRaw(file, "|u1", rows, cols, data);
    }

    private static void writeNpyRaw(Path file, String dtype, int rows, int cols, byte[] body) throws IOException {
        String header = "{'descr': '" + dtype + "', 'fortran_order': False, 'shape': (" + rows + ", " + cols + "), }";
        int total = 10 + header.length() + 1;
        int pad = (64 - total % 64) % 64;
        header = header + " ".repeat(pad) + "\n";
        try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(file))) {
            o.write(new byte[]{(byte) 0x93, 'N', 'U', 'M', 'P', 'Y', 1, 0});
            int hl = header.length();
            o.write(new byte[]{(byte) (hl & 0xFF), (byte) (hl >> 8)});
            o.write(header.getBytes(StandardCharsets.US_ASCII));
            o.write(body);
        }
    }
}
