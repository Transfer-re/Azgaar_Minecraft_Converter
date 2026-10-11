package com.example.border;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Server-to-client packet carrying border overlay data.
 */
public record BorderDataPayload(
        boolean enabled,
        int modeId,
        int regionId,
        int colorRgb,
        String name,
        float[] xs,
        float[] zs
) {

    private static final String PROTOCOL = "1";
    // optional on both ends: a client or server without the mod can still connect
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new Identifier("fantasymapgenerator", "border_data"), () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL), NetworkRegistry.acceptMissingOr(PROTOCOL));

    public static void register() {
        CHANNEL.messageBuilder(BorderDataPayload.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BorderDataPayload::write)
                .decoder(BorderDataPayload::read)
                // the lambda body only loads the client class when a packet
                // arrives, so a dedicated server never touches it
                .consumerMainThread((payload, context) -> ClientBorderOverlay.receive(payload))
                .add();
    }

    public static boolean canSend(ServerPlayerEntity player) {
        return CHANNEL.isRemotePresent(player.networkHandler.connection);
    }

    public static void send(ServerPlayerEntity player, BorderDataPayload payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    private static BorderDataPayload read(PacketByteBuf buf) {
        boolean enabled = buf.readBoolean();
        if (!enabled) {
            return new BorderDataPayload(false, 0, 0, 0xFFFFFF, "", new float[0], new float[0]);
        }

        int modeId = buf.readVarInt();
        int regionId = buf.readVarInt();
        int colorRgb = buf.readInt();
        String name = buf.readString(256);

        int count = buf.readVarInt();
        float[] xs = new float[count];
        float[] zs = new float[count];
        for (int i = 0; i < count; i++) {
            xs[i] = buf.readFloat();
            zs[i] = buf.readFloat();
        }

        return new BorderDataPayload(true, modeId, regionId, colorRgb, name, xs, zs);
    }

    public void write(PacketByteBuf buf) {
        buf.writeBoolean(enabled);
        if (!enabled) {
            return;
        }

        buf.writeVarInt(modeId);
        buf.writeVarInt(regionId);
        buf.writeInt(colorRgb);
        buf.writeString(name, 256);

        int count = Math.min(xs.length, zs.length);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeFloat(xs[i]);
            buf.writeFloat(zs[i]);
        }
    }

}
