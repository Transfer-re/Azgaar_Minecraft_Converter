package com.example.worldmap.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.example.fmg.FMGCell;
import com.example.fmg.FMGHeightSampler;
import com.example.fmg.FMGMapData;
import com.example.fmg.FMGProvince;
import com.example.fmg.FMGState;
import com.example.worldmap.runtime.MapImageCache;
import com.example.worldmap.runtime.MapImageData;
import com.example.worldgen.ImageMapChunkGenerator;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.world.gen.chunk.ChunkGenerator;

/**
 * Tracks player movement across FMG provinces/states and shows a title
 * only when the region actually changes.
 */
public final class PlayerRegionTracker {

    private static final int CHECK_INTERVAL_TICKS = 10; // every 0.5s

    private static final Map<UUID, Integer> LAST_STATE = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LAST_PROVINCE = new ConcurrentHashMap<>();

    private PlayerRegionTracker() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % CHECK_INTERVAL_TICKS != 0) {
                return;
            }

            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                trackPlayer(player);
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUuid();
            LAST_STATE.remove(id);
            LAST_PROVINCE.remove(id);
        });
    }

    private static void trackPlayer(ServerPlayerEntity player) {
        ChunkGenerator generator = player.getServerWorld().getChunkManager().getChunkGenerator();
        if (!(generator instanceof ImageMapChunkGenerator fmgGenerator)) {
            return; // non-FMG dimension
        }

        MapImageData cached = MapImageCache.get(fmgGenerator.mapInfo());
        FMGMapData map = cached.fmgData();

        FMGCell cell = FMGHeightSampler.findCellAtWorldPos(map, player.getBlockX(), player.getBlockZ());
        if (cell == null) {
            return;
        }

        int stateId = cell.getState();
        Integer provinceId = map.getProvinceIdForCell(cell.getI());
        UUID playerId = player.getUuid();

        Integer prevState = LAST_STATE.put(playerId, stateId);
        Integer prevProvince = LAST_PROVINCE.put(playerId, provinceId);

        if (Objects.equals(prevState, stateId) && Objects.equals(prevProvince, provinceId)) {
            return; // no region change
        }

        FMGState state = map.getState(stateId);
        FMGProvince province = provinceId != null ? map.getProvince(provinceId) : null;

        Text title = buildStateTitle(state);
        Text subtitle = buildProvinceSubtitle(province);


        player.networkHandler.sendPacket(
                new TitleFadeS2CPacket(10, 80, 20)
        );
        player.networkHandler.sendPacket(
                new TitleS2CPacket(title)
        );
        player.networkHandler.sendPacket(
                new SubtitleS2CPacket(subtitle)
        );
    }

    private static Text buildStateTitle(FMGState state) {
        String name = state != null && state.getName() != null ? state.getName() : "Unknown State";
        TextColor color = parseColor(state != null ? state.getColor() : null, Formatting.GOLD);
        return Text.literal(name).styled(style -> style.withBold(true).withColor(color));
    }

    private static Text buildProvinceSubtitle(FMGProvince province) {
        String name = province != null && province.getName() != null ? province.getName() : "Unclaimed Province";
        TextColor color = parseColor(province != null ? province.getColor() : null, Formatting.GRAY);
        return Text.literal(name).styled(style -> style.withItalic(true).withColor(color));
    }

    private static TextColor parseColor(String raw, Formatting fallback) {
        TextColor parsed = raw != null ? TextColor.parse(raw).result().orElse(null) : null;
        return parsed != null ? parsed : TextColor.fromFormatting(fallback);
    }
}
