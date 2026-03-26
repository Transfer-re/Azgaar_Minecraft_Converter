package com.example.worldmap.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.example.border.BorderDataPayload;
import com.example.border.BorderMode;
import com.example.border.FMGBorderPoints;
import com.example.fmg.FMGCell;
import com.example.fmg.FMGHeightSampler;
import com.example.fmg.FMGMapData;
import com.example.worldgen.ImageMapChunkGenerator;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.gen.chunk.ChunkGenerator;

/**
 * Server-authoritative border viewer toggle.
 *
 * <p>Players opt-in via command; while enabled, the server refreshes the border whenever
 * the player's current province/state changes.</p>
 */
public final class PlayerBorderViewer {

    private static final int CHECK_INTERVAL_TICKS = 10;
    private static final int MAX_SEGMENTS = 6000;

    private static final Map<UUID, BorderMode> MODES = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LAST_REGION_ID = new ConcurrentHashMap<>();

    private PlayerBorderViewer() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % CHECK_INTERVAL_TICKS != 0) {
                return;
            }
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                tickPlayer(player);
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUuid();
            MODES.remove(id);
            LAST_REGION_ID.remove(id);
        });
    }

    public static BorderMode getMode(ServerPlayerEntity player) {
        return MODES.getOrDefault(player.getUuid(), BorderMode.OFF);
    }

    public static void setMode(ServerPlayerEntity player, BorderMode mode) {
        UUID id = player.getUuid();
        if (mode == null) {
            mode = BorderMode.OFF;
        }

        if (mode == BorderMode.OFF) {
            MODES.remove(id);
            LAST_REGION_ID.remove(id);
            sendDisabled(player);
            return;
        }

        MODES.put(id, mode);
        // Force immediate resend.
        LAST_REGION_ID.remove(id);
        tickPlayer(player);
    }

    private static void tickPlayer(ServerPlayerEntity player) {
        BorderMode mode = getMode(player);
        if (mode == BorderMode.OFF) {
            return;
        }

        ChunkGenerator generator = player.getServerWorld().getChunkManager().getChunkGenerator();
        if (!(generator instanceof ImageMapChunkGenerator fmgGenerator)) {
            sendDisabled(player);
            MODES.remove(player.getUuid());
            LAST_REGION_ID.remove(player.getUuid());
            return;
        }

        var cached = MapImageCache.get(fmgGenerator.mapInfo());
        FMGMapData map = cached.fmgData();

        FMGCell cell = FMGHeightSampler.findCellAtWorldPos(map, player.getBlockX(), player.getBlockZ());
        if (cell == null) {
            return;
        }

        Integer regionId = switch (mode) {
            case PROVINCE -> {
                Integer provinceId = map.getProvinceIdForCell(cell.getI());
                int raw = provinceId != null ? provinceId : cell.getProvince();
                yield raw <= 0 ? com.example.border.FMGBorderPoints.NEUTRAL_PROVINCE_ID : raw;
            }
            case STATE -> cell.getState();
            default -> null;
        };

        if (regionId == null) {
            player.sendMessage(Text.literal("No region here to outline."), true);
            return;
        }

        Integer prev = LAST_REGION_ID.put(player.getUuid(), regionId);
        if (Objects.equals(prev, regionId)) {
            return;
        }

        int viewDistanceChunks = player.getServerWorld().getServer().getPlayerManager().getViewDistance();
        double radiusBlocks = Math.max(2, viewDistanceChunks) * 16.0;
        double centerX = player.getX();
        double centerZ = player.getZ();

        FMGBorderPoints.Result result = switch (mode) {
            case PROVINCE -> FMGBorderPoints.computeProvinceBorderPoints(map, regionId, MAX_SEGMENTS, centerX, centerZ, radiusBlocks);
            case STATE -> FMGBorderPoints.computeStateBorderPoints(map, regionId, MAX_SEGMENTS, centerX, centerZ, radiusBlocks);
            default -> FMGBorderPoints.Result.empty();
        };

        sendBorderData(player, mode, regionId, result);
    }

    private static void sendDisabled(ServerPlayerEntity player) {
        if (!ServerPlayNetworking.canSend(player, BorderDataPayload.ID)) {
            return;
        }
        ServerPlayNetworking.send(player, new BorderDataPayload(false, 0, 0, 0xFFFFFF, "", new float[0], new float[0]));
    }

    private static void sendBorderData(
            ServerPlayerEntity player,
            BorderMode mode,
            int regionId,
            FMGBorderPoints.Result result
    ) {
        if (!ServerPlayNetworking.canSend(player, BorderDataPayload.ID)) {
            return;
        }

        var segments = result.segments();
        float[] xs = new float[segments.size() * 2];
        float[] zs = new float[segments.size() * 2];
        for (int i = 0; i < segments.size(); i++) {
            var s = segments.get(i);
            xs[i * 2] = (float) s.a().worldX();
            zs[i * 2] = (float) s.a().worldZ();
            xs[i * 2 + 1] = (float) s.b().worldX();
            zs[i * 2 + 1] = (float) s.b().worldZ();
        }

        ServerPlayNetworking.send(
                player,
                new BorderDataPayload(true, mode.id, regionId, result.colorRgb(), result.name(), xs, zs)
        );
    }
}
