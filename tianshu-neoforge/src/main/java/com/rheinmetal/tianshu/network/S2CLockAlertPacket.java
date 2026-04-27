package com.rheinmetal.tianshu.network;

import com.rheinmetal.tianshu.Tianshu;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [零信任架构] 服务端 -> 客户端：定向锁定信息包
 *
 * 设计约束：
 * 1. 仅 S2C 方向，严禁 C2S。
 * 2. 只发送给【被锁定】的玩家，不广播。
 * 3. 包体极简：只传锁定该玩家的实体 UUID 列表。
 */
public record S2CLockAlertPacket(List<UUID> lockedEntityUuids) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2CLockAlertPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Tianshu.MOD_ID, "lock_alert"));

    public static final StreamCodec<ByteBuf, S2CLockAlertPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.collection(ArrayList::new, UUIDUtil.STREAM_CODEC),
            S2CLockAlertPacket::lockedEntityUuids,
            S2CLockAlertPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * UUID 的 ByteBuf 编解码辅助类
     */
    public static final class UUIDUtil {
        public static final StreamCodec<ByteBuf, UUID> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public void encode(ByteBuf buf, UUID uuid) {
                buf.writeLong(uuid.getMostSignificantBits());
                buf.writeLong(uuid.getLeastSignificantBits());
            }

            @Override
            public UUID decode(ByteBuf buf) {
                long most = buf.readLong();
                long least = buf.readLong();
                return new UUID(most, least);
            }
        };
    }
}
