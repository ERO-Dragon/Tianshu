package com.rheinmetal.tianshu.mc.network;

import com.rheinmetal.tianshu.api.server.ServerActionResult;
import com.rheinmetal.tianshu.api.ModIdentity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** The server's answer to one {@link ServerActionRequestPacket}. */
public record ServerActionResultPacket(
        String requestId,
        ServerActionResult.Status status,
        String message
) implements CustomPacketPayload {

    public static final Type<ServerActionResultPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(ModIdentity.MOD_ID, "server_action_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerActionResultPacket> STREAM_CODEC =
            StreamCodec.ofMember(ServerActionResultPacket::encode, ServerActionResultPacket::decode);

    public ServerActionResultPacket {
        requestId = ServerActionLimits.text(requestId, "requestId", ServerActionLimits.MAX_ID_LENGTH);
        status = status == null ? ServerActionResult.Status.FAILED : status;
        message = message == null ? "" : message.trim();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(requestId);
        buffer.writeUtf(status.name());
        buffer.writeUtf(message);
    }

    static ServerActionResultPacket decode(RegistryFriendlyByteBuf buffer) {
        String requestId = ServerActionLimits.text(buffer.readUtf(), "requestId", ServerActionLimits.MAX_ID_LENGTH);
        String status = buffer.readUtf();
        ServerActionResult.Status parsed;
        try {
            parsed = ServerActionResult.Status.valueOf(status);
        } catch (IllegalArgumentException unknown) {
            parsed = ServerActionResult.Status.FAILED;
        }
        String message = ServerActionLimits.text(buffer.readUtf(), "message", ServerActionLimits.MAX_VALUE_LENGTH);
        return new ServerActionResultPacket(requestId, parsed, message);
    }
}
