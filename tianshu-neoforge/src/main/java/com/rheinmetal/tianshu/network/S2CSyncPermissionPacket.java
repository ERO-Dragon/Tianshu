package com.rheinmetal.tianshu.network;

import com.rheinmetal.tianshu.Tianshu;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record S2CSyncPermissionPacket(boolean allowAutoEquip, boolean allowAutoTrash, boolean allowHighPrecisionMode) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2CSyncPermissionPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Tianshu.MOD_ID, "sync_permission"));

    public static final StreamCodec<ByteBuf, S2CSyncPermissionPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL,
            S2CSyncPermissionPacket::allowAutoEquip,
            ByteBufCodecs.BOOL,
            S2CSyncPermissionPacket::allowAutoTrash,
            ByteBufCodecs.BOOL,
            S2CSyncPermissionPacket::allowHighPrecisionMode,
            S2CSyncPermissionPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
