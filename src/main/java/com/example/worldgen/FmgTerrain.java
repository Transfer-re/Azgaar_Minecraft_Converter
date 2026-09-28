package com.example.worldgen;

import java.util.List;
import java.util.Map;

import com.example.fmg.FMGCell;
import com.example.fmg.FMGHeightSampler;
import com.example.fmg.FMGMapData;
import com.example.fmg.FMGRiver;
import com.example.fmg.FMGRoute;
import com.example.fmg.FMGRouteSampler;
import com.example.heightmap.HeightmapPostConfig;
import com.example.terrain.TerrainBuilder;
import com.example.terrain.TerrainInput;
import com.example.terrain.TerrainParams;
import com.example.terrain.TerrainRaster;
import com.example.worldmap.MapInfo;

/**
 * Glue between the loaded FMG map and the Minecraft-free terrain bake
 * ({@link TerrainBuilder}): turns cells / rivers / routes into a
 * {@link TerrainInput} using the same FMG -> world projection as the rest of
 * the generator.
 */
public final class FmgTerrain {

    private FmgTerrain() {
    }

    public static TerrainRaster build(FMGMapData map, MapInfo mapInfo, TerrainBuilder.Progress progress) {
        return TerrainBuilder.build(input(map, mapInfo), params(mapInfo), progress);
    }

    /** The terrain parameters for this world (config section + per-world detail settings). */
    public static TerrainParams params(MapInfo mapInfo) {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        TerrainParams p = gson.fromJson(gson.toJson(HeightmapPostConfig.get().terrain), TerrainParams.class);
        // the create-world "surface detail" switch and amount scale the per-block detail
        double k = mapInfo.surfaceDetail() ? mapInfo.detailAmplitude() / MapInfo.DEFAULT_DETAIL_AMPLITUDE : 0.0;
        p.fineDetail *= k;
        p.cragDetail *= mapInfo.surfaceDetail() ? 1.0 : 0.0;
        return p;
    }

    /** Seed for the terrain noise: the configured one, else derived from the export name. */
    public static long seed(MapInfo mapInfo) {
        long s = HeightmapPostConfig.get().seed;
        if (s != 0) return s;
        return mapInfo.fmgExport() == null ? 1L : mapInfo.fmgExport().hashCode() * 0x9E3779B97F4A7C15L + 0x7E77A1L;
    }

    public static TerrainInput input(FMGMapData map, MapInfo mapInfo) {
        int w = map.getInfo().getWidth();
        int h = map.getInfo().getHeight();
        double scale = FMGHeightSampler.sampleScale();
        TerrainInput in = new TerrainInput(w, h);
        in.scale = scale;
        in.originX = -(w * scale) / 2.0;
        in.originZ = -(h * scale) / 2.0;
        in.seaLevel = mapInfo.seaLevel();
        in.minY = mapInfo.minY();
        in.maxY = mapInfo.maxY();
        in.seed = seed(mapInfo);

        List<FMGCell> cells = map.getCells();
        double[] packXY = new double[cells.size() * 2];
        int[] biome = new int[cells.size()];
        float[] lake = new float[cells.size()];
        Map<Integer, Double> lakes = map.getLakeHeights();
        for (int i = 0; i < cells.size(); i++) {
            FMGCell c = cells.get(i);
            packXY[2 * i] = c.getPx();
            packXY[2 * i + 1] = c.getPy();
            biome[i] = c.getBiome();
            Double lh = lakes == null ? null : lakes.get(c.getF());
            lake[i] = lh == null ? 0f : lh.floatValue();
        }
        double[] gridXY = map.getGridPoints();
        float[] gridH = map.getGridHeights();
        if (gridXY == null || gridH == null) {
            // older exports without grid points: fall back to pack cells for heights too
            gridXY = packXY;
            gridH = new float[cells.size()];
            for (int i = 0; i < cells.size(); i++) gridH[i] = cells.get(i).getH();
        }
        in.rasterise(gridXY, gridH, packXY, biome, lake);

        if (map.getRivers() != null) {
            for (FMGRiver r : map.getRivers()) {
                if (r.getCells() == null) continue;
                double[] xs = new double[r.getCells().length];
                double[] ys = new double[r.getCells().length];
                int n = 0;
                for (int cellId : r.getCells()) {
                    FMGCell c = map.getCell(cellId);
                    if (c == null || c.getP() == null) continue;
                    xs[n] = c.getPx();
                    ys[n] = c.getPy();
                    n++;
                }
                if (n < 2) continue;
                TerrainInput.River rv = new TerrainInput.River();
                rv.id = r.getI();
                rv.parent = r.getParent();
                rv.discharge = r.getDischarge();
                rv.widthKm = r.getWidth();
                rv.sourceWidthKm = r.getSourceWidth();
                rv.x = java.util.Arrays.copyOf(xs, n);
                rv.y = java.util.Arrays.copyOf(ys, n);
                in.rivers.add(rv);
            }
        }

        if (map.getRoutes() != null) {
            for (FMGRoute route : map.getRoutes()) {
                List<FMGRouteSampler.RouteSample> s = FMGRouteSampler.getSampledRoute(route.getI());
                if (s == null || s.size() < 2) continue;
                TerrainInput.Route rt = new TerrainInput.Route();
                rt.id = route.getI();
                rt.road = "roads".equals(route.getGroup());
                rt.x = new double[s.size()];
                rt.y = new double[s.size()];
                for (int i = 0; i < s.size(); i++) {
                    rt.x[i] = s.get(i).x();
                    rt.y[i] = s.get(i).y();
                }
                in.routes.add(rt);
            }
        }
        return in;
    }
}
