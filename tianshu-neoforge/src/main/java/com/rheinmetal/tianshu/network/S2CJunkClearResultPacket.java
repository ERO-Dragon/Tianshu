package com.rheinmetal.tianshu.network;

import com.rheinmetal.tianshu.Tianshu;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record S2CJunkClearResultPacket(boolean success, int stackCount, int itemCount, String message) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CJunkClearResultPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Tianshu.MOD_ID, "junk_clear_result"));

    public static final StreamCodec<ByteBuf, S2CJunkClearResultPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL,
            S2CJunkClearResultPacket::success,
            ByteBufCodecs.VAR_INT,
            S2CJunkClearResultPacket::stackCount,
            ByteBufCodecs.VAR_INT,
            S2CJunkClearResultPacket::itemCount,
            ByteBufCodecs.stringUtf8(128),
            S2CJunkClearResultPacket::message,
            S2CJunkClearResultPacket::new
    );

    public S2CJunkClearResultPacket {
        stackCount = Math.max(0, stackCount);
        itemCount = Math.max(0, itemCount);
        message = message == null ? "" : message;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
