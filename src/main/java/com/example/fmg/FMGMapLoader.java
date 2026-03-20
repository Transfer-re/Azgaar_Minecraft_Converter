package com.example.fmg;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Loads FMG Full JSON exports into memory
 */
public class FMGMapLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(FMGMapLoader.class);
    
    /**
     * Load an FMG map from a JSON file
     */
    public static FMGMapData loadMap(Path jsonFile) throws IOException {
        LOGGER.info("Loading FMG map from: {}", jsonFile);
        
        Gson gson = new Gson();
        FMGMapData mapData = new FMGMapData();
        
        try (FileReader reader = new FileReader(jsonFile.toFile())) {
            JsonObject root = gson.fromJson(reader, JsonObject.class);

            // Load info
            if (root.has("info")) {
                mapData.setInfo(parseInfo(root.getAsJsonObject("info")));
            }

            // Load pack data (cells, burgs, states are inside "pack")
            JsonObject pack = null;
            if (root.has("pack")) {
                pack = root.getAsJsonObject("pack");

                if (pack.has("cells")) {
                    mapData.setCells(parseCells(pack.getAsJsonArray("cells")));
                }

                if (pack.has("vertices")) {
                    mapData.setVertices(parseVertices(pack.getAsJsonArray("vertices")));
                }

                if (pack.has("burgs")) {
                    mapData.setBurgs(parseBurgs(pack.getAsJsonArray("burgs")));
                }

                if (pack.has("states")) {
                    mapData.setStates(parseStates(pack.getAsJsonArray("states")));
                }

                if (pack.has("routes")) {
                    mapData.setRoutes(parseRoutes(pack.getAsJsonArray("routes")));
                }

                if (pack.has("rivers")) {
                    mapData.setRivers(parseRivers(pack.getAsJsonArray("rivers")));
                }

                if (pack.has("provinces")) {
                    mapData.setProvinces(parseProvinces(pack.getAsJsonArray("provinces")));
                }
            }

            // Some exports may place provinces outside of "pack".
            if (mapData.getProvinces() == null && root.has("provinces")) {
                mapData.setProvinces(parseProvinces(root.getAsJsonArray("provinces")));
            }

            // FMG exports can represent biomes either as an array of
            // objects ("biomes") or as a columnar structure
            // ("biomesData" with parallel i/name/color arrays).
            // Some versions place this under "pack", others at the root.
            JsonObject biomesSource = null;
            if (pack != null && (pack.has("biomesData") || pack.has("biomes"))) {
                biomesSource = pack;
            } else if (root.has("biomesData") || root.has("biomes")) {
                biomesSource = root;
            }

            if (biomesSource != null) {
                if (biomesSource.has("biomesData")) {
                    mapData.setBiomes(parseBiomesData(biomesSource.getAsJsonObject("biomesData")));
                } else if (biomesSource.has("biomes")) {
                    mapData.setBiomes(parseBiomes(biomesSource.getAsJsonArray("biomes")));
                }
            }
        }
        
        LOGGER.info("Map loaded successfully: {} cells, {} burgs, {} biomes, {} states, {} provinces, {} routes, {} rivers",
                mapData.getCells() != null ? mapData.getCells().size() : 0,
                mapData.getBurgs() != null ? mapData.getBurgs().size() : 0,
                mapData.getBiomes() != null ? mapData.getBiomes().size() : 0,
            mapData.getStates() != null ? mapData.getStates().size() : 0,
            mapData.getProvinces() != null ? mapData.getProvinces().size() : 0,
            mapData.getRoutes() != null ? mapData.getRoutes().size() : 0,
            mapData.getRivers() != null ? mapData.getRivers().size() : 0);
        
        return mapData;
    }
    
    private static FMGMapInfo parseInfo(JsonObject infoObj) {
        FMGMapInfo info = new FMGMapInfo();
        
        if (infoObj.has("version")) info.setVersion(infoObj.get("version").getAsString());
        if (infoObj.has("mapName")) info.setMapName(infoObj.get("mapName").getAsString());
        if (infoObj.has("width")) info.setWidth(infoObj.get("width").getAsInt());
        if (infoObj.has("height")) info.setHeight(infoObj.get("height").getAsInt());
        if (infoObj.has("seed")) info.setSeed(infoObj.get("seed").getAsString());
        
        return info;
    }
    
    private static List<FMGCell> parseCells(JsonArray cellsArray) {
        List<FMGCell> cells = new ArrayList<>();
        
        for (JsonElement elem : cellsArray) {
            JsonObject cellObj = elem.getAsJsonObject();
            FMGCell cell = new FMGCell();
            
            if (cellObj.has("i")) cell.setI(cellObj.get("i").getAsInt());
            
            if (cellObj.has("p")) {
                JsonArray pArray = cellObj.getAsJsonArray("p");
                double[] p = new double[2];
                p[0] = pArray.get(0).getAsDouble();
                p[1] = pArray.get(1).getAsDouble();
                cell.setP(p);
            }

            if (cellObj.has("v")) {
                JsonArray vArray = cellObj.getAsJsonArray("v");
                int[] v = new int[vArray.size()];
                for (int idx = 0; idx < vArray.size(); idx++) {
                    v[idx] = vArray.get(idx).getAsInt();
                }
                cell.setV(v);
            }

            if (cellObj.has("c")) {
                JsonArray cArray = cellObj.getAsJsonArray("c");
                int[] c = new int[cArray.size()];
                for (int idx = 0; idx < cArray.size(); idx++) {
                    c[idx] = cArray.get(idx).getAsInt();
                }
                cell.setC(c);
            }
            
            if (cellObj.has("h")) cell.setH(cellObj.get("h").getAsInt());
            if (cellObj.has("biome")) cell.setBiome(cellObj.get("biome").getAsInt());
            if (cellObj.has("fl")) cell.setFl(cellObj.get("fl").getAsInt());
            if (cellObj.has("r")) cell.setR(cellObj.get("r").getAsInt());
            if (cellObj.has("burg")) cell.setBurg(cellObj.get("burg").getAsInt());
            if (cellObj.has("state")) cell.setState(cellObj.get("state").getAsInt());
            if (cellObj.has("province")) cell.setProvince(cellObj.get("province").getAsInt());
            if (cellObj.has("area")) cell.setArea(cellObj.get("area").getAsDouble());
            if (cellObj.has("t")) cell.setT(cellObj.get("t").getAsDouble());
            
            if (cellObj.has("routes")) {
                Map<String, Integer> routes = new HashMap<>();
                JsonObject routesObj = cellObj.getAsJsonObject("routes");
                for (String key : routesObj.keySet()) {
                    routes.put(key, routesObj.get(key).getAsInt());
                }
                cell.setRoutes(routes);
            }
            
            cells.add(cell);
        }
        
        return cells;
    }

    private static List<FMGVertex> parseVertices(JsonArray verticesArray) {
        List<FMGVertex> vertices = new ArrayList<>();

        for (JsonElement elem : verticesArray) {
            JsonObject vObj = elem.getAsJsonObject();
            FMGVertex vertex = new FMGVertex();

            if (vObj.has("i")) vertex.setI(vObj.get("i").getAsInt());

            if (vObj.has("p")) {
                JsonArray pArray = vObj.getAsJsonArray("p");
                double[] p = new double[2];
                p[0] = pArray.get(0).getAsDouble();
                p[1] = pArray.get(1).getAsDouble();
                vertex.setP(p);
            }

            if (vObj.has("c")) {
                JsonArray cArray = vObj.getAsJsonArray("c");
                int[] c = new int[cArray.size()];
                for (int idx = 0; idx < cArray.size(); idx++) {
                    c[idx] = cArray.get(idx).getAsInt();
                }
                vertex.setC(c);
            }

            if (vObj.has("v")) {
                JsonArray vArray = vObj.getAsJsonArray("v");
                int[] v = new int[vArray.size()];
                for (int idx = 0; idx < vArray.size(); idx++) {
                    v[idx] = vArray.get(idx).getAsInt();
                }
                vertex.setV(v);
            }

            vertices.add(vertex);
        }

        return vertices;
    }
    
    private static List<FMGBurg> parseBurgs(JsonArray burgsArray) {
        List<FMGBurg> burgs = new ArrayList<>();
        
        for (JsonElement elem : burgsArray) {
            if (!elem.isJsonObject()) {
                // Some exports keep placeholder zeros (or nulls) in the array; skip them safely.
                continue;
            }

            JsonObject burgObj = elem.getAsJsonObject();
            FMGBurg burg = new FMGBurg();
            
            if (burgObj.has("i")) burg.setI(burgObj.get("i").getAsInt());
            if (burgObj.has("name")) burg.setName(burgObj.get("name").getAsString());
            if (burgObj.has("cell")) burg.setCell(burgObj.get("cell").getAsInt());
            if (burgObj.has("population")) burg.setPopulation(burgObj.get("population").getAsInt());
            if (burgObj.has("capital")) burg.setCapital(burgObj.get("capital").getAsInt());
            if (burgObj.has("x")) burg.setX(burgObj.get("x").getAsDouble());
            if (burgObj.has("y")) burg.setY(burgObj.get("y").getAsDouble());

            if (burgObj.has("culture")) burg.setCulture(burgObj.get("culture").getAsInt());
            if (burgObj.has("state")) burg.setState(burgObj.get("state").getAsInt());
            if (burgObj.has("feature")) burg.setFeature(burgObj.get("feature").getAsInt());
            if (burgObj.has("type")) burg.setType(burgObj.get("type").getAsString());
            if (burgObj.has("MFCG")) burg.setMfcg(burgObj.get("MFCG").getAsInt());
            if (burgObj.has("link")) burg.setLink(burgObj.get("link").getAsString());
            if (burgObj.has("port")) burg.setPort(burgObj.get("port").getAsInt());
            if (burgObj.has("citadel")) burg.setCitadel(burgObj.get("citadel").getAsInt());
            if (burgObj.has("plaza")) burg.setPlaza(burgObj.get("plaza").getAsInt());
            if (burgObj.has("shanty")) burg.setShanty(burgObj.get("shanty").getAsInt());
            if (burgObj.has("temple")) burg.setTemple(burgObj.get("temple").getAsInt());
            if (burgObj.has("walls")) burg.setWalls(burgObj.get("walls").getAsInt());
            if (burgObj.has("lock")) burg.setLock(burgObj.get("lock").getAsBoolean());
            if (burgObj.has("removed")) burg.setRemoved(burgObj.get("removed").getAsBoolean());
            
            burgs.add(burg);
        }
        
        return burgs;
    }

    private static List<FMGRoute> parseRoutes(JsonArray routesArray) {
        List<FMGRoute> routes = new ArrayList<>();

        for (JsonElement elem : routesArray) {
            JsonObject routeObj = elem.getAsJsonObject();
            FMGRoute route = new FMGRoute();

            if (routeObj.has("group")) {
                String group = routeObj.get("group").getAsString();
                // Only keep land routes for now
                if (!"roads".equals(group) && !"trails".equals(group)) {
                    continue;
                }
                route.setGroup(group);
            } else {
                // If group is missing, skip as we don't know how to categorize it
                continue;
            }

            if (routeObj.has("i")) route.setI(routeObj.get("i").getAsInt());
            if (routeObj.has("feature")) route.setFeature(routeObj.get("feature").getAsInt());
            if (routeObj.has("name")) route.setName(routeObj.get("name").getAsString());
            if (routeObj.has("lock")) route.setLock(routeObj.get("lock").getAsBoolean());

            if (routeObj.has("points")) {
                JsonArray pts = routeObj.getAsJsonArray("points");
                List<FMGRoute.FMGRoutePoint> list = new ArrayList<>();

                // FMG spec says points is number[] [x, y, cellId, ...],
                // but actual exports may use [[x, y, cellId], ...].
                // Support both representations.
                if (!pts.isEmpty() && pts.get(0).isJsonArray()) {
                    // Array of triplets: [[x, y, cellId], ...]
                    for (JsonElement element : pts) {
                        JsonArray triple = element.getAsJsonArray();
                        if (triple.size() >= 3) {
                            double x = triple.get(0).getAsDouble();
                            double y = triple.get(1).getAsDouble();
                            int cellId = triple.get(2).getAsInt();
                            list.add(new FMGRoute.FMGRoutePoint(x, y, cellId));
                        }
                    }
                } else {
                    // Flat array: [x, y, cellId, x, y, cellId, ...]
                    for (int idx = 0; idx + 2 < pts.size(); idx += 3) {
                        double x = pts.get(idx).getAsDouble();
                        double y = pts.get(idx + 1).getAsDouble();
                        int cellId = pts.get(idx + 2).getAsInt();
                        list.add(new FMGRoute.FMGRoutePoint(x, y, cellId));
                    }
                }

                route.setPoints(list);
            }

            routes.add(route);
        }

        return routes;
    }

    private static List<FMGRiver> parseRivers(JsonArray riversArray) {
        List<FMGRiver> rivers = new ArrayList<>();

        for (JsonElement elem : riversArray) {
            JsonObject riverObj = elem.getAsJsonObject();
            FMGRiver river = new FMGRiver();

            if (riverObj.has("i")) river.setI(riverObj.get("i").getAsInt());
            if (riverObj.has("name")) river.setName(riverObj.get("name").getAsString());
            if (riverObj.has("type")) river.setType(riverObj.get("type").getAsString());
            if (riverObj.has("source")) river.setSource(riverObj.get("source").getAsInt());
            if (riverObj.has("mouth")) river.setMouth(riverObj.get("mouth").getAsInt());
            if (riverObj.has("parent")) river.setParent(riverObj.get("parent").getAsInt());
            if (riverObj.has("basin")) river.setBasin(riverObj.get("basin").getAsInt());

            if (riverObj.has("cells")) {
                JsonArray cellsArr = riverObj.getAsJsonArray("cells");
                int[] cells = new int[cellsArr.size()];
                for (int idx = 0; idx < cellsArr.size(); idx++) {
                    cells[idx] = cellsArr.get(idx).getAsInt();
                }
                river.setCells(cells);
            }

            if (riverObj.has("points")) {
                JsonArray ptsArr = riverObj.getAsJsonArray("points");
                List<double[]> pts = new ArrayList<>();
                for (JsonElement pElem : ptsArr) {
                    JsonArray pArr = pElem.getAsJsonArray();
                    if (pArr.size() >= 2) {
                        double x = pArr.get(0).getAsDouble();
                        double y = pArr.get(1).getAsDouble();
                        pts.add(new double[]{x, y});
                    }
                }
                river.setPoints(pts);
            }

            if (riverObj.has("discharge")) river.setDischarge(riverObj.get("discharge").getAsDouble());
            if (riverObj.has("length")) river.setLength(riverObj.get("length").getAsDouble());
            if (riverObj.has("width")) river.setWidth(riverObj.get("width").getAsDouble());
            if (riverObj.has("sourceWidth")) river.setSourceWidth(riverObj.get("sourceWidth").getAsDouble());

            rivers.add(river);
        }

        return rivers;
    }
    
    private static List<FMGBiome> parseBiomes(JsonArray biomesArray) {
        List<FMGBiome> biomes = new ArrayList<>();
        
        for (JsonElement elem : biomesArray) {
            JsonObject biomeObj = elem.getAsJsonObject();
            FMGBiome biome = new FMGBiome();
            
            if (biomeObj.has("i")) biome.setI(biomeObj.get("i").getAsInt());
            if (biomeObj.has("name")) biome.setName(biomeObj.get("name").getAsString());
            if (biomeObj.has("color")) biome.setColor(biomeObj.get("color").getAsString());
            
            biomes.add(biome);
        }
        
        return biomes;
    }

    /**
     * Parse the newer FMG "biomesData" structure:
     * {
     *   "i":    [0, 1, 2, ...],
     *   "name": ["Marine", "Hot desert", ...],
     *   "color":["#466eab", "#fbe79f", ...]
     * }
     */
    private static List<FMGBiome> parseBiomesData(JsonObject biomesData) {
        List<FMGBiome> biomes = new ArrayList<>();

        if (biomesData == null) {
            return biomes;
        }

        JsonArray ids = biomesData.has("i") ? biomesData.getAsJsonArray("i") : null;
        JsonArray names = biomesData.has("name") ? biomesData.getAsJsonArray("name") : null;
        JsonArray colors = biomesData.has("color") ? biomesData.getAsJsonArray("color") : null;

        if (ids == null || names == null) {
            LOGGER.warn("FMG biomesData is missing 'i' or 'name' arrays");
            return biomes;
        }

        int count = ids.size();
        for (int idx = 0; idx < count; idx++) {
            FMGBiome biome = new FMGBiome();

            JsonElement idElem = ids.get(idx);
            if (!idElem.isJsonNull()) {
                biome.setI(idElem.getAsInt());
            }

            if (idx < names.size() && !names.get(idx).isJsonNull()) {
                biome.setName(names.get(idx).getAsString());
            }

            if (colors != null && idx < colors.size() && !colors.get(idx).isJsonNull()) {
                biome.setColor(colors.get(idx).getAsString());
            }

            biomes.add(biome);
        }

        return biomes;
    }
    
    private static List<FMGProvince> parseProvinces(JsonArray provincesArray) {
        List<FMGProvince> provinces = new ArrayList<>();

        for (JsonElement elem : provincesArray) {
            if (!elem.isJsonObject()) {
                // Some exports keep placeholder zeros in the array; skip them safely.
                continue;
            }

            JsonObject provinceObj = elem.getAsJsonObject();
            FMGProvince province = new FMGProvince();

            if (provinceObj.has("i")) province.setI(provinceObj.get("i").getAsInt());
            if (provinceObj.has("name")) province.setName(provinceObj.get("name").getAsString());
            if (provinceObj.has("state")) province.setState(provinceObj.get("state").getAsInt());
            if (provinceObj.has("color")) province.setColor(provinceObj.get("color").getAsString());

            if (provinceObj.has("cells")) {
                JsonArray cellsArray = provinceObj.getAsJsonArray("cells");
                List<Integer> cells = new ArrayList<>(cellsArray.size());
                for (JsonElement cellElem : cellsArray) {
                    cells.add(cellElem.getAsInt());
                }
                province.setCells(cells);
            }

            provinces.add(province);
        }

        return provinces;
    }

    private static List<FMGState> parseStates(JsonArray statesArray) {
        List<FMGState> states = new ArrayList<>();
        
        for (JsonElement elem : statesArray) {
            JsonObject stateObj = elem.getAsJsonObject();
            FMGState state = new FMGState();
            
            if (stateObj.has("i")) state.setI(stateObj.get("i").getAsInt());
            if (stateObj.has("name")) state.setName(stateObj.get("name").getAsString());
            if (stateObj.has("capital")) state.setCapital(stateObj.get("capital").getAsInt());
            if (stateObj.has("color")) state.setColor(stateObj.get("color").getAsString());
            
            states.add(state);
        }
        
        return states;
    }
}
