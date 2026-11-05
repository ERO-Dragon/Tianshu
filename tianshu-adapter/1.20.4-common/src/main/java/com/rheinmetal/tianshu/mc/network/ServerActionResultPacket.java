package com.rheinmetal.tianshu.mc.network;

import com.rheinmetal.tianshu.api.server.ServerActionResult;
import com.rheinmetal.tianshu.api.ModIdentity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The server's answer to one {@link ServerActionRequestPacket}. */
public record ServerActionResultPacket(
        String requestId,
        ServerActionResult.Status status,
        String message
) implements CustomPacketPayload {

    /** The wire identity: 1.20.4 payloads carry a plain channel id and write themselves. */
    @Override
    public ResourceLocation id() {
        return CHANNEL;
    }

    @Override
    public void write(FriendlyByteBuf buffer) {
        encode(buffer);
    }
    /** The channel this packet travels on; both loaders register the same id so the wire shape stays identical. */
    public static final ResourceLocation CHANNEL =
            new ResourceLocation(ModIdentity.MOD_ID, "server_action_result");

    public ServerActionResultPacket {
        requestId = ServerActionLimits.text(requestId, "requestId", ServerActionLimits.MAX_ID_LENGTH);
        status = status == null ? ServerActionResult.Status.FAILED : status;
        message = message == null ? "" : message.trim();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(requestId);
        buffer.writeUtf(status.name());
        buffer.writeUtf(message);
    }

    public static ServerActionResultPacket decode(FriendlyByteBuf buffer) {
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
