package com.example.heightmap;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.example.fmg.FMGCell;
import com.example.fmg.FMGHeightSampler;
import com.example.fmg.FMGMapData;
import com.example.fmg.FMGRiver;
import com.example.worldmap.MapInfo;
import com.example.worldmap.runtime.MapImageData;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Dumps the generator's raw FMG-projected heightfield (the height BEFORE any
 * chunk-time road shaping / carving) over a square region to
 * {@code <gameDir>/fmg_heightmaps/}, plus a river polyline sidecar, so
 * post-processing experiments can run against a fixed file instead of the live
 * world. Sampling only touches the cached FMG heightfield -- no chunk
 * generation -- so a large radius is cheap.
 */
public final class HeightmapExport {

    private HeightmapExport() {
    }

    public record Result(Path fmgh, Path heightPng, Path hillshadePng, Path riversFile,
                         int width, int height, float minY, float maxY, int riverCount, long millis) {
    }

    public static Path outputDir() {
        Path base;
        try {
            base = FabricLoader.getInstance().getGameDir();
        } catch (Throwable t) {
            base = Path.of(".");
        }
        return base.resolve("fmg_heightmaps");
    }

    /**
     * @param name  base file name (no extension); {@code null}/blank -> timestamped
     */
    public static Result export(MapImageData image, MapInfo info,
                                 int centerX, int centerZ, int radius, String name) throws IOException {
        long t0 = System.currentTimeMillis();
        int size = 2 * radius + 1;
        int originX = centerX - radius;
        int originZ = centerZ - radius;

        HeightField field = new HeightField(originX, originZ, size, size,
                info.seaLevel(), info.minY(), info.maxY());
        for (int z = 0; z < size; z++) {
            int wz = originZ + z;
            for (int x = 0; x < size; x++) {
                field.set(x, z, image.sampleHeightY(originX + x, wz));
            }
        }

        String base = (name == null || name.isBlank())
                ? "heightmap_" + centerX + "_" + centerZ + "_r" + radius
                    + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                : name.replaceAll("[^A-Za-z0-9._-]", "_");

        Path dir = outputDir();
        Files.createDirectories(dir);
        Path fmgh = dir.resolve(base + ".fmgh");
        Path heightPng = dir.resolve(base + ".height.png");
        Path shadePng = dir.resolve(base + ".hillshade.png");
        Path riversFile = dir.resolve(base + ".rivers");

        field.write(fmgh);
        field.writeHeightPng(heightPng);
        field.writeHillshadePng(shadePng);
        int riverCount = writeRivers(riversFile, image.fmgData(), originX, originZ, size, size);

        return new Result(fmgh, heightPng, shadePng, riversFile,
                size, size, field.min(), field.max(), riverCount, System.currentTimeMillis() - t0);
    }

    /**
     * Writes every FMG river polyline that passes anywhere near the exported
     * window, in WORLD coordinates, as a dependency-free text file:
     * <pre>
     *   # fmg-heightmap-rivers originX originZ width height
     *   river &lt;id&gt; width=&lt;km&gt; sourceWidth=&lt;km&gt; discharge=&lt;m3s&gt; points=&lt;n&gt;
     *   &lt;worldX&gt; &lt;worldZ&gt;
     *   ...
     * </pre>
     * Coordinates can fall outside [origin, origin+size); the harness clips.
     */
    private static int writeRivers(Path file, FMGMapData map, int originX, int originZ, int w, int h)
            throws IOException {
        // pad the accept window generously so a river just outside still carves inward
        int pad = 64;
        int loX = originX - pad, hiX = originX + w + pad;
        int loZ = originZ - pad, hiZ = originZ + h + pad;

        int written = 0;
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("# fmg-heightmap-rivers " + originX + " " + originZ + " " + w + " " + h);
            out.newLine();

            for (HeightPostProcess.River river : worldRivers(map)) {
                boolean anyInside = false;
                for (int[] p : river.worldPoints) {
                    if (p[0] >= loX && p[0] < hiX && p[1] >= loZ && p[1] < hiZ) { anyInside = true; break; }
                }
                if (!anyInside) continue;

                out.write(String.format(Locale.ROOT,
                        "river %d width=%.3f sourceWidth=%.3f discharge=%.3f points=%d",
                        river.id, river.widthKm, river.sourceWidthKm, river.discharge, river.worldPoints.size()));
                out.newLine();
                for (int[] p : river.worldPoints) {
                    out.write(p[0] + " " + p[1]);
                    out.newLine();
                }
                written++;
            }
        }
        return written;
    }

    /**
     * Every FMG river as a {@link HeightPostProcess.River}: polyline transformed
     * to WORLD coordinates (same FMG->world transform the height sampler uses),
     * carrying its width and discharge. Shared by the file export and the
     * in-world bake so both carve identical channels.
     */
    public static List<HeightPostProcess.River> worldRivers(FMGMapData map) {
        List<HeightPostProcess.River> out = new ArrayList<>();
        if (map == null || map.getRivers() == null || map.getInfo() == null) return out;
        double scale = FMGHeightSampler.sampleScale();
        double offX = -(map.getInfo().getWidth() * scale) / 2.0;
        double offZ = -(map.getInfo().getHeight() * scale) / 2.0;

        for (FMGRiver river : map.getRivers()) {
            List<double[]> fmgPts = polyline(river, map);
            if (fmgPts.size() < 2) continue;
            HeightPostProcess.River r = new HeightPostProcess.River(
                    river.getI(), river.getWidth(), river.getSourceWidth(), river.getDischarge());
            for (double[] p : fmgPts) {
                r.add((int) Math.round(p[0] * scale + offX), (int) Math.round(p[1] * scale + offZ));
            }
            out.add(r);
        }
        return out;
    }

    /** River polyline in FMG space: prefer explicit points, fall back to cell centres. */
    private static List<double[]> polyline(FMGRiver river, FMGMapData map) {
        List<double[]> pts = new ArrayList<>();
        if (river.getPoints() != null && river.getPoints().size() >= 2) {
            for (double[] p : river.getPoints()) {
                if (p != null && p.length >= 2) pts.add(new double[]{p[0], p[1]});
            }
            return pts;
        }
        if (river.getCells() != null && map != null) {
            for (int cellId : river.getCells()) {
                FMGCell c = map.getCell(cellId);
                if (c != null && c.getP() != null && c.getP().length >= 2) {
                    pts.add(new double[]{c.getPx(), c.getPy()});
                }
            }
        }
        return pts;
    }
}
