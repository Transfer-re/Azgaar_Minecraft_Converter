package com.example.border;

import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

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
) implements FabricPacket {

    public static final PacketType<BorderDataPayload> TYPE = PacketType.create(
            new Identifier("fantasymapgenerator", "border_data"), BorderDataPayload::read);

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

    @Override
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

    @Override
    public PacketType<?> getType() {
        return TYPE;
    }
}
