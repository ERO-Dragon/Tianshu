package com.rheinmetal.tianshu.mc.network;

import com.rheinmetal.tianshu.api.ModIdentity;
import com.rheinmetal.tianshu.api.tool.ToolWireLimits;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * One tool call a client sends to the optional server component.
 *
 * <p>{@code toolId} is a namespaced tool id; the server validates it against its own registry, so an unknown id is
 * answerable rather than dangerous. {@code argsJson} is the argument object as text.</p>
 */
public record ToolCallRequestPacket(String requestId, String toolId, String argsJson) implements CustomPacketPayload {

    public static final Type<ToolCallRequestPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(ModIdentity.MOD_ID, "tool_call"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ToolCallRequestPacket> STREAM_CODEC =
            StreamCodec.ofMember(ToolCallRequestPacket::encode, ToolCallRequestPacket::decode);

    public ToolCallRequestPacket {
        requestId = ToolWireLimits.text(requestId, "requestId", ToolWireLimits.MAX_REQUEST_ID);
        toolId = ToolWireLimits.text(toolId, "toolId", ToolWireLimits.MAX_TOOL_ID);
        argsJson = argsJson == null ? "{}" : argsJson;
        if (argsJson.length() > ToolWireLimits.MAX_JSON) {
            throw new IllegalArgumentException("tool arguments are too large: " + argsJson.length());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(requestId);
        buffer.writeUtf(toolId);
        buffer.writeUtf(argsJson);
    }

    static ToolCallRequestPacket decode(RegistryFriendlyByteBuf buffer) {
        return new ToolCallRequestPacket(buffer.readUtf(), buffer.readUtf(), buffer.readUtf());
    }
}
