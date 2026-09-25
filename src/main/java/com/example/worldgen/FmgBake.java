package com.example.worldgen;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import com.example.fmg.FMGBiomeVariantMap;
import com.example.fmg.FMGHeightSampler;
import com.example.fmg.FMGMapData;
import com.example.fmg.FMGRiverNetwork;
import com.example.fmg.FMGRouteIndex;
import com.example.heightmap.HeightmapPostConfig;
import com.example.worldmap.MapInfo;
import com.example.worldmap.runtime.FMGMapDataCache;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

/**
 * One-time "Preparing Fantasy Map…" step: build the heavy per-map structures
 * (heightfield, road index, river network, climate) and persist them to
 * {@code <world>/fmg/bake/} so every later load of the world is instant.
 *
 * <p>Run (blocking, on the server thread) from {@code ServerWorldEvents.LOAD}
 * for the overworld, before spawn-chunk generation. A {@code signature.txt}
 * (hash of the map JSON + scale + the config the bake depends on) triggers a
 * rebuild when anything relevant changed.</p>
 */
public final class FmgBake {

    private static final Logger LOGGER = LoggerFactory.getLogger("FmgBake");
    private static final Gson GSON = new Gson();
    private static final int FORMAT_VERSION = 5;

    private FmgBake() {
    }

    // ---- progress (read by the client while the loading screen is up) ----

    private static volatile boolean active;
    private static volatile String phase = "";
    private static volatile float fraction;

    public static boolean isActive() {
        return active;
    }

    public static String phase() {
        return phase;
    }

    public static float fraction() {
        return fraction;
    }

    private static void progress(String p, float f) {
        phase = p;
        fraction = f;
        LOGGER.info("[bake] {}  {}%", p, Math.round(f * 100));
    }

    // ---- read() hook: use the baked heightfield instead of rebuilding ----

    private static volatile Path loadingBakeDir;

    /**
     * Called from {@link FMGMapDataCache} after the JSON parse. Returns true (and
     * fills {@code data}'s cached heightfield) when a fresh bake is being loaded,
     * so the caller skips the expensive rebuild.
     */
    public static boolean applyBakedHeightField(FMGMapData data) {
        Path dir = loadingBakeDir;
        if (dir == null) {
            return false;
        }
        Path file = dir.resolve("height.bin");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            data.setCachedHeightField(readHeightField(file));
            return true;
        } catch (IOException e) {
            LOGGER.warn("Baked heightfield unreadable; rebuilding", e);
            return false;
        }
    }

    // ---- entry point ----

    public static void ensureBaked(MinecraftServer server, MapInfo mapInfo) {
        active = true;
        try {
            Path dir = server.getSavePath(WorldSavePath.ROOT).resolve("fmg").resolve("bake");
            String export = mapInfo.fmgExport();
            String sig = signature(mapInfo);
            boolean fresh = sig != null && sig.equals(readSignature(dir));

            if (fresh && tryLoad(dir, export, mapInfo)) {
                progress("Ready", 1.0f);
                return;
            }
            if (fresh) {
                // a partial load left stale entries in the caches -- start clean
                FMGMapDataCache.invalidate();
                FMGRouteIndex.invalidate();
                FMGRiverNetwork.invalidate();
                FMGBiomeVariantMap.invalidate();
            }

            progress("Preparing Fantasy Map", 0.05f);
            FMGMapData map = FMGMapDataCache.load(export);   // parse + route sampler + heightfield

            progress("Building heightfield", 0.15f);
            FMGHeightSampler.sampleHeight(map, 0, 0);

            progress("Indexing roads", 0.45f);
            FMGRouteIndex routes = FMGRouteIndex.get(export, map);

            progress("Carving rivers", 0.60f);
            FMGRiverNetwork rivers = FMGRiverNetwork.get(
                    export, map, mapInfo.minY(), mapInfo.maxY(), mapInfo.seaLevel());

            progress("Assigning biomes", 0.85f);
            FMGBiomeVariantMap biomes = FMGBiomeVariantMap.get(
                    export, map, com.example.profile.FmgProfiles.get(mapInfo.profile()));

            progress("Saving", 0.95f);
            Files.createDirectories(dir);
            try (DataOutputStream o = data(dir.resolve("routes.bin"))) {
                routes.writeTo(o);
            }
            try (DataOutputStream o = data(dir.resolve("rivers.bin"))) {
                rivers.writeTo(o);
            }
            try (DataOutputStream o = data(dir.resolve("biomes.bin"))) {
                biomes.writeTo(o);
            }
            double[][] hf = map.getCachedHeightField();
            if (hf != null) {
                writeHeightField(dir.resolve("height.bin"), hf);
            }
            if (sig != null) {
                Files.writeString(dir.resolve("signature.txt"), sig, StandardCharsets.UTF_8);
            }
            progress("Ready", 1.0f);
            LOGGER.info("FMG bake written to {}", dir);
        } catch (Exception e) {
            LOGGER.error("FMG bake failed -- world will fall back to lazy generation", e);
        } finally {
            loadingBakeDir = null;
            active = false;
        }
    }

    private static boolean tryLoad(Path dir, String export, MapInfo mapInfo) {
        progress("Loading baked map", 0.1f);
        try {
            try (DataInputStream in = dataIn(dir.resolve("routes.bin"))) {
                FMGRouteIndex.seed(export, FMGRouteIndex.readFrom(in));
            }
            try (DataInputStream in = dataIn(dir.resolve("rivers.bin"))) {
                FMGRiverNetwork.seed(export, FMGRiverNetwork.readFrom(in));
            }
            try (DataInputStream in = dataIn(dir.resolve("biomes.bin"))) {
                FMGBiomeVariantMap.seed(export, com.example.profile.FmgProfiles.get(mapInfo.profile()),
                        FMGBiomeVariantMap.readFrom(in));
            }
            loadingBakeDir = dir;                 // read() will pick up height.bin
            progress("Loading map", 0.5f);
            FMGMapDataCache.load(export);         // parse + route sampler + baked heightfield
            LOGGER.info("Loaded FMG bake from {}", dir);
            return true;
        } catch (Exception e) {
            LOGGER.warn("Baked FMG data unreadable ({}); rebuilding", e.toString());
            return false;
        } finally {
            loadingBakeDir = null;
        }
    }

    // ---- signature ----

    private static String signature(MapInfo mapInfo) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(mapJsonBytes(mapInfo));
            md.update(Double.toString(mapInfo.scale()).getBytes(StandardCharsets.UTF_8));
            md.update(mapInfo.profile().getBytes(StandardCharsets.UTF_8));
            md.update(GSON.toJson(com.example.profile.FmgProfiles.get(mapInfo.profile()))
                    .getBytes(StandardCharsets.UTF_8));
            md.update(GSON.toJson(HeightmapPostConfig.get()).getBytes(StandardCharsets.UTF_8));
            md.update(new byte[]{(byte) FORMAT_VERSION});
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            LOGGER.warn("Could not compute bake signature (will always rebuild)", e);
            return null;
        }
    }

    private static byte[] mapJsonBytes(MapInfo mapInfo) throws IOException {
        Path fmgDir = FmgWorldSettings.fmgDir();
        if (fmgDir != null && Files.isRegularFile(fmgDir.resolve("map.json"))) {
            return Files.readAllBytes(fmgDir.resolve("map.json"));
        }
        Path src = Path.of(mapInfo.fmgExport().replace('\\', '/'));
        if (!src.isAbsolute()) {
            src = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(src);
        }
        return Files.readAllBytes(src);
    }

    private static String readSignature(Path dir) {
        try {
            Path f = dir.resolve("signature.txt");
            return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ---- heightfield format:  int w, int h, then w*h doubles (x-outer) ----

    private static void writeHeightField(Path file, double[][] hf) throws IOException {
        int w = hf.length;
        int h = w == 0 ? 0 : hf[0].length;
        try (DataOutputStream o = data(file)) {
            o.writeInt(w);
            o.writeInt(h);
            for (double[] col : hf) {
                for (double v : col) {
                    o.writeDouble(v);
                }
            }
        }
    }

    private static double[][] readHeightField(Path file) throws IOException {
        try (DataInputStream in = dataIn(file)) {
            int w = in.readInt();
            int h = in.readInt();
            double[][] hf = new double[w][h];
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < h; z++) {
                    hf[x][z] = in.readDouble();
                }
            }
            return hf;
        }
    }

    private static DataOutputStream data(Path file) throws IOException {
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)));
    }

    private static DataInputStream dataIn(Path file) throws IOException {
        return new DataInputStream(new BufferedInputStream(Files.newInputStream(file)));
    }
}
