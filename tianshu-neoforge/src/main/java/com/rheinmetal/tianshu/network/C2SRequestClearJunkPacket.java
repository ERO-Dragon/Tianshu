package com.rheinmetal.tianshu.network;

import com.rheinmetal.tianshu.Tianshu;
import com.rheinmetal.tianshu.function.junk.JunkItemIdPolicy;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record C2SRequestClearJunkPacket(List<String> itemIds) implements CustomPacketPayload {
    public static final int MAX_ITEM_IDS = 256;

    public static final CustomPacketPayload.Type<C2SRequestClearJunkPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Tianshu.MOD_ID, "request_clear_junk"));

    public static final StreamCodec<ByteBuf, C2SRequestClearJunkPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.stringUtf8(128)),
            C2SRequestClearJunkPacket::itemIds,
            C2SRequestClearJunkPacket::new
    );

    public C2SRequestClearJunkPacket {
        itemIds = itemIds == null ? List.of() : itemIds.stream()
                .filter(JunkItemIdPolicy::canPersistAsJunk)
                .distinct()
                .limit(MAX_ITEM_IDS)
                .toList();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
