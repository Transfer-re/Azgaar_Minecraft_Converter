package com.example.worldgen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.fmg.FMGBurg;
import com.example.fmg.FMGHeightSampler;
import com.example.fmg.FMGMapData;
import com.example.worldmap.runtime.MapImageCache;
import com.example.worldmap.runtime.MapImageData;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Spawns a village at each FMG burg location, once the relevant chunk is generated/loaded.
 */
public final class BurgVillageSpawner {

    private static final Logger LOGGER = LoggerFactory.getLogger("BurgVillageSpawner");

    private static final String STATE_KEY = "fantasymapgenerator_burg_villages";

    // Flatness gate for villages. Mirrors the structure-based gate in BurgVillageStructure.
    // If an area is too steep, we place an outpost instead of a village.
    private static final int FLATNESS_RADIUS_BLOCKS = 32;
    private static final int FLATNESS_SAMPLE_STEP_BLOCKS = 8;
    private static final int FLATNESS_MAX_RELIEF_BLOCKS = 14;
    private static final double FLATNESS_MAX_MEAN_SLOPE = 0.70; // blocks up per block traveled

    private static final String OUTPOST_STRUCTURE_ID = "minecraft:pillager_outpost";

    private static final Map<String, Map<Long, List<BurgPos>>> BURG_POS_CACHE = new HashMap<>();

    private static final class BurgPos {
        final int burgId;
        final int x;
        final int z;

        BurgPos(int burgId, int x, int z) {
            this.burgId = burgId;
            this.x = x;
            this.z = z;
        }
    }

    private BurgVillageSpawner() {
    }

    public static void register() {
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (!(world instanceof ServerWorld serverWorld)) {
                return;
            }

            if (!(serverWorld.getChunkManager().getChunkGenerator() instanceof ImageMapChunkGenerator fmgGenerator)) {
                return;
            }

            trySpawnForChunk(serverWorld, fmgGenerator, chunk);
        });
    }

    private static void trySpawnForChunk(ServerWorld world, ImageMapChunkGenerator generator, WorldChunk chunk) {
        MapImageData cached = MapImageCache.get(generator.mapInfo());
        FMGMapData map = cached.fmgData();
        if (map == null || map.getInfo() == null || map.getBurgs() == null || map.getBurgs().isEmpty()) {
            return;
        }

        String exportKey = generator.mapInfo().fmgExport();
        Map<Long, List<BurgPos>> byChunk = BURG_POS_CACHE.computeIfAbsent(exportKey, k -> computeBurgChunkMap(map));

        ChunkPos cp = chunk.getPos();
        long key = ChunkPos.toLong(cp.x, cp.z);
        List<BurgPos> burgs = byChunk.get(key);
        if (burgs == null || burgs.isEmpty()) {
            return;
        }

        BurgVillageState state = getState(world);
        BurgVillageConfig.Config config = BurgVillageConfig.loadOrCreate();

        for (BurgPos burg : burgs) {
            if (state.isSpawned(burg.burgId)) {
                continue;
            }

            // If the terrain is too steep for a village, place an outpost instead.
            if (!isAreaFlatEnoughForVillage(world, burg.x, burg.z)) {
                int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE_WG, burg.x, burg.z);
                BlockPos placePos = new BlockPos(burg.x, topY, burg.z);

                boolean ok = runPlaceStructure(world.getServer(), world, placePos, OUTPOST_STRUCTURE_ID);
                if (ok) {
                    state.markSpawned(burg.burgId);
                    LOGGER.info("Placed outpost ({}) at burg {} @ {},{},{} (village terrain too steep)", OUTPOST_STRUCTURE_ID, burg.burgId, placePos.getX(), placePos.getY(), placePos.getZ());
                }
                continue;
            }

            int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE_WG, burg.x, burg.z);
            BlockPos placePos = new BlockPos(burg.x, topY, burg.z);

            String structureId = pickVillageStructureId(world, map, placePos, config, burg.burgId);
            if (structureId == null) {
                continue;
            }

            boolean ok = runPlaceStructure(world.getServer(), world, placePos, structureId);
            if (ok) {
                state.markSpawned(burg.burgId);
                LOGGER.info("Placed village ({}) at burg {} @ {},{},{}", structureId, burg.burgId, placePos.getX(), placePos.getY(), placePos.getZ());
            } else {
                boolean outpostOk = runPlaceStructure(world.getServer(), world, placePos, OUTPOST_STRUCTURE_ID);
                if (outpostOk) {
                    state.markSpawned(burg.burgId);
                    LOGGER.info("Placed outpost ({}) at burg {} @ {},{},{} (village placement failed)", OUTPOST_STRUCTURE_ID, burg.burgId, placePos.getX(), placePos.getY(), placePos.getZ());
                }
            }
        }
    }

    private static boolean isAreaFlatEnoughForVillage(ServerWorld world, int centerX, int centerZ) {
        final int radius = FLATNESS_RADIUS_BLOCKS;
        final int step = FLATNESS_SAMPLE_STEP_BLOCKS;

        final int size = (radius * 2 / step) + 1;
        int[] heights = new int[size * size];

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (int ix = 0; ix < size; ix++) {
            int x = centerX - radius + (ix * step);
            for (int iz = 0; iz < size; iz++) {
                int z = centerZ - radius + (iz * step);

                int h = world.getTopY(Heightmap.Type.WORLD_SURFACE_WG, x, z);
                heights[(ix * size) + iz] = h;
                if (h < min) min = h;
                if (h > max) max = h;
            }
        }

        int relief = max - min;
        if (relief > FLATNESS_MAX_RELIEF_BLOCKS) {
            return false;
        }

        double slopeSum = 0.0;
        int slopeCount = 0;

        for (int ix = 0; ix < size; ix++) {
            for (int iz = 0; iz < size; iz++) {
                int h = heights[(ix * size) + iz];

                if (ix + 1 < size) {
                    int hx = heights[((ix + 1) * size) + iz];
                    slopeSum += Math.abs(hx - h) / (double) step;
                    slopeCount++;
                }
                if (iz + 1 < size) {
                    int hz = heights[(ix * size) + (iz + 1)];
                    slopeSum += Math.abs(hz - h) / (double) step;
                    slopeCount++;
                }
            }
        }

        double meanSlope = slopeCount == 0 ? 0.0 : (slopeSum / slopeCount);
        return meanSlope <= FLATNESS_MAX_MEAN_SLOPE;
    }

    private static Map<Long, List<BurgPos>> computeBurgChunkMap(FMGMapData map) {
        Map<Long, List<BurgPos>> byChunk = new HashMap<>();

        final double mapW = map.getInfo().getWidth();
        final double mapH = map.getInfo().getHeight();
        final double scale = FMGHeightSampler.sampleScale();
        final double offsetX = -(mapW * scale) / 2.0;
        final double offsetZ = -(mapH * scale) / 2.0;

        for (FMGBurg burg : map.getBurgs()) {
            if (burg == null || burg.isRemoved()) {
                continue;
            }

            int centerX = (int) Math.round((burg.getX() * scale) + offsetX);
            int centerZ = (int) Math.round((burg.getY() * scale) + offsetZ);

            int chunkX = centerX >> 4;
            int chunkZ = centerZ >> 4;
            long key = ChunkPos.toLong(chunkX, chunkZ);

            byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(new BurgPos(burg.getI(), centerX, centerZ));
        }

        return byChunk;
    }

    private static BurgVillageState getState(ServerWorld world) {
        PersistentStateManager mgr = world.getPersistentStateManager();
        return mgr.getOrCreate(BurgVillageState.TYPE, STATE_KEY);
    }

    private static String pickVillageStructureId(ServerWorld world, FMGMapData mapData, BlockPos pos, BurgVillageConfig.Config config, int burgId) {
        RegistryEntry<Biome> biomeEntry = world.getBiome(pos);
        Identifier biomeId = world.getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.BIOME)
                .getId(biomeEntry.value());

        String biomeKey = biomeId != null ? biomeId.toString() : "";

        List<String> types = null;
        String fmgKey = FmgBiomeKeySampler.sampleNormalizedKey(mapData, pos.getX(), pos.getZ());
        if (fmgKey != null && !fmgKey.isBlank()) {
            types = config.biomeVillageTypes().get("fmg:" + fmgKey);
        }
        if (types == null || types.isEmpty()) {
            types = config.biomeVillageTypes().get(biomeKey);
        }
        if (types == null || types.isEmpty()) {
            types = config.defaultVillageTypes();
        }
        if (types == null || types.isEmpty()) {
            types = List.of("plains");
        }

        // Deterministic choice by burg id.
        String type = types.get(Math.floorMod(burgId, types.size()));
        BurgVillageConfig.VillageType typeCfg = config.villageTypes().get(type);
        if (typeCfg == null || typeCfg.startPool() == null) {
            type = "plains";
            typeCfg = config.villageTypes().get(type);
        }

        // Prefer mapping based on vanilla pool naming.
        String pool = typeCfg != null ? typeCfg.startPool() : "";
        String derived = deriveVillageStructureIdFromPool(pool);
        return derived != null ? derived : "minecraft:village_plains";
    }

    private static String deriveVillageStructureIdFromPool(String startPool) {
        if (startPool == null) {
            return null;
        }
        // Example: minecraft:village/plains/town_centers -> minecraft:village_plains
        String s = startPool.trim();
        if (!s.startsWith("minecraft:village/")) {
            return null;
        }
        String rest = s.substring("minecraft:village/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        String type = rest.substring(0, slash);
        return "minecraft:village_" + type;
    }

    private static boolean runPlaceStructure(MinecraftServer server, ServerWorld world, BlockPos pos, String structureId) {
        // Use the built-in /place structure command to leverage vanilla structure generation.
        // We run it at the burg center position so it places after terrain is done.
        ServerCommandSource source = server.getCommandSource()
                .withWorld(world)
                .withPosition(Vec3d.ofCenter(pos))
                .withRotation(Vec2f.ZERO)
                .withLevel(4);

        String cmd = "place structure " + structureId;
        try {
            server.getCommandManager().executeWithPrefix(source, cmd);
            return true;
        } catch (Exception ex) {
            LOGGER.warn("Failed to run command '{}' at {}", cmd, pos, ex);
            return false;
        }
    }
}
