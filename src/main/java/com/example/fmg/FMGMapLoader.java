package com.example.fmg;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

                if (pack.has("burgs")) {
                    mapData.setBurgs(parseBurgs(pack.getAsJsonArray("burgs")));
                }

                if (pack.has("states")) {
                    mapData.setStates(parseStates(pack.getAsJsonArray("states")));
                }
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
        
        LOGGER.info("Map loaded successfully: {} cells, {} burgs, {} biomes, {} states",
                mapData.getCells() != null ? mapData.getCells().size() : 0,
                mapData.getBurgs() != null ? mapData.getBurgs().size() : 0,
                mapData.getBiomes() != null ? mapData.getBiomes().size() : 0,
                mapData.getStates() != null ? mapData.getStates().size() : 0);
        
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
            
            if (cellObj.has("h")) cell.setH(cellObj.get("h").getAsInt());
            if (cellObj.has("biome")) cell.setBiome(cellObj.get("biome").getAsInt());
            if (cellObj.has("fl")) cell.setFl(cellObj.get("fl").getAsInt());
            if (cellObj.has("r")) cell.setR(cellObj.get("r").getAsInt());
            if (cellObj.has("burg")) cell.setBurg(cellObj.get("burg").getAsInt());
            if (cellObj.has("state")) cell.setState(cellObj.get("state").getAsInt());
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
    
    private static List<FMGBurg> parseBurgs(JsonArray burgsArray) {
        List<FMGBurg> burgs = new ArrayList<>();
        
        for (JsonElement elem : burgsArray) {
            JsonObject burgObj = elem.getAsJsonObject();
            FMGBurg burg = new FMGBurg();
            
            if (burgObj.has("i")) burg.setI(burgObj.get("i").getAsInt());
            if (burgObj.has("name")) burg.setName(burgObj.get("name").getAsString());
            if (burgObj.has("cell")) burg.setCell(burgObj.get("cell").getAsInt());
            if (burgObj.has("population")) burg.setPopulation(burgObj.get("population").getAsInt());
            if (burgObj.has("capital")) burg.setCapital(burgObj.get("capital").getAsInt());
            if (burgObj.has("x")) burg.setX(burgObj.get("x").getAsDouble());
            if (burgObj.has("y")) burg.setY(burgObj.get("y").getAsDouble());
            
            burgs.add(burg);
        }
        
        return burgs;
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
    
    private static List<FMGState> parseStates(JsonArray statesArray) {
        List<FMGState> states = new ArrayList<>();
        
        for (JsonElement elem : statesArray) {
            JsonObject stateObj = elem.getAsJsonObject();
            FMGState state = new FMGState();
            
            if (stateObj.has("i")) state.setI(stateObj.get("i").getAsInt());
            if (stateObj.has("name")) state.setName(stateObj.get("name").getAsString());
            if (stateObj.has("capital")) state.setCapital(stateObj.get("capital").getAsInt());
            
            states.add(state);
        }
        
        return states;
    }
}
