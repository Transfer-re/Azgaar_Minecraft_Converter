package com.example.border;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server-to-client payload carrying border overlay data.
 */
public record BorderDataPayload(
        boolean enabled,
        int modeId,
        int regionId,
        int colorRgb,
        String name,
        float[] xs,
        float[] zs
) implements CustomPayload {

    public static final CustomPayload.Id<BorderDataPayload> ID = new CustomPayload.Id<>(
            Identifier.of("fantasymapgenerator", "border_data")
    );

    public static final PacketCodec<RegistryByteBuf, BorderDataPayload> CODEC = new PacketCodec<>() {
        @Override
        public BorderDataPayload decode(RegistryByteBuf buf) {
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
        public void encode(RegistryByteBuf buf, BorderDataPayload value) {
            buf.writeBoolean(value.enabled());
            if (!value.enabled()) {
                return;
            }

            buf.writeVarInt(value.modeId());
            buf.writeVarInt(value.regionId());
            buf.writeInt(value.colorRgb());
            buf.writeString(value.name(), 256);

            int count = Math.min(value.xs().length, value.zs().length);
            buf.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                buf.writeFloat(value.xs()[i]);
                buf.writeFloat(value.zs()[i]);
            }
        }
    };

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
