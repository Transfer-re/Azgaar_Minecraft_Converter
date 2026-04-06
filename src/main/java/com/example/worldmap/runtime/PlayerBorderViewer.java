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

    // Border geometry is generated within a radius around the player.
    // If the player travels far within the same region (province/state), the previously
    // sent geometry can end up entirely outside the client's view, making it look like
    // borders "randomly" stopped rendering. Refresh when moving far enough.
    private static final double RESEND_FRACTION_OF_RADIUS = 0.35;
    private static final double MIN_RESEND_DISTANCE_BLOCKS = 64.0;

    private static final Map<UUID, BorderMode> MODES = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LAST_REGION_ID = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_CENTER_XZ = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LAST_RADIUS_BLOCKS = new ConcurrentHashMap<>();

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
            LAST_CENTER_XZ.remove(id);
            LAST_RADIUS_BLOCKS.remove(id);
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
            LAST_CENTER_XZ.remove(id);
            LAST_RADIUS_BLOCKS.remove(id);
            sendDisabled(player);
            return;
        }

        MODES.put(id, mode);
        // Force immediate resend.
        LAST_REGION_ID.remove(id);
        LAST_CENTER_XZ.remove(id);
        LAST_RADIUS_BLOCKS.remove(id);
        tickPlayer(player);
    }

    private static void tickPlayer(ServerPlayerEntity player) {
        BorderMode mode = Objects.requireNonNullElse(getMode(player), BorderMode.OFF);
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
        int viewDistanceChunks = player.getServerWorld().getServer().getPlayerManager().getViewDistance();
        int radiusBlocks = (int) Math.round(Math.max(2, viewDistanceChunks) * 16.0);
        double centerX = player.getX();
        double centerZ = player.getZ();

        boolean regionChanged = !Objects.equals(prev, regionId);
        boolean movedFarEnough = shouldResendForMovement(player.getUuid(), centerX, centerZ, radiusBlocks);
        boolean radiusChanged = shouldResendForRadius(player.getUuid(), radiusBlocks);

        if (!regionChanged && !movedFarEnough && !radiusChanged) {
            return;
        }

        // Record send parameters.
        LAST_CENTER_XZ.put(player.getUuid(), packBlockXZ(centerX, centerZ));
        LAST_RADIUS_BLOCKS.put(player.getUuid(), radiusBlocks);

        FMGBorderPoints.Result result = switch (mode) {
            case PROVINCE -> FMGBorderPoints.computeProvinceBorderPoints(map, regionId, MAX_SEGMENTS, centerX, centerZ, radiusBlocks);
            case STATE -> FMGBorderPoints.computeStateBorderPoints(map, regionId, MAX_SEGMENTS, centerX, centerZ, radiusBlocks);
            default -> FMGBorderPoints.Result.empty();
        };

        sendBorderData(player, mode, regionId, result);
    }

    private static boolean shouldResendForMovement(UUID playerId, double centerX, double centerZ, int radiusBlocks) {
        Long packed = LAST_CENTER_XZ.get(playerId);
        if (packed == null) {
            return true;
        }
        int lastX = (int) (packed >> 32);
        int lastZ = (int) packed.longValue();

        double dx = centerX - lastX;
        double dz = centerZ - lastZ;
        double distSq = dx * dx + dz * dz;

        double threshold = Math.max(MIN_RESEND_DISTANCE_BLOCKS, radiusBlocks * RESEND_FRACTION_OF_RADIUS);
        return distSq >= threshold * threshold;
    }

    private static boolean shouldResendForRadius(UUID playerId, int radiusBlocks) {
        Integer last = LAST_RADIUS_BLOCKS.get(playerId);
        if (last == null) {
            return true;
        }
        return !Objects.equals(last, radiusBlocks);
    }

    private static long packBlockXZ(double x, double z) {
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        return ((long) ix << 32) | (iz & 0xFFFFFFFFL);
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
