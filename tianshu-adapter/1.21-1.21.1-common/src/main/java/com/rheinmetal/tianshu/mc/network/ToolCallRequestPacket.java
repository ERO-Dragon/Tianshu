package com.rheinmetal.tianshu.mc.network;

import com.rheinmetal.tianshu.api.ModIdentity;
import com.rheinmetal.tianshu.api.tool.ToolWireLimits;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * One tool call a client sends to the optional server component.
 *
 * <p>{@code toolId} is a namespaced tool id; the server validates it against its own registry, so an unknown id is
 * answerable rather than dangerous. {@code argsJson} is the argument object as text.</p>
 */
/**
 * One tool call on its way to the server.
 *
 * <p>{@code callerModule} is the module id that is asking, or empty when the player is asking directly. The server
 * never takes the caller from the packet beyond that id: the <em>player</em> half of the identity is the connection's
 * own player, so "acting for a player" cannot be claimed by anyone else.</p>
 */
public record ToolCallRequestPacket(String requestId, String toolId, String argsJson, String callerModule)
        implements CustomPacketPayload {

    public static final Type<ToolCallRequestPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "tool_call"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ToolCallRequestPacket> STREAM_CODEC =
            StreamCodec.ofMember(ToolCallRequestPacket::encode, ToolCallRequestPacket::decode);

    public ToolCallRequestPacket {
        requestId = ToolWireLimits.text(requestId, "requestId", ToolWireLimits.MAX_REQUEST_ID);
        toolId = ToolWireLimits.text(toolId, "toolId", ToolWireLimits.MAX_TOOL_ID);
        argsJson = argsJson == null ? "{}" : argsJson;
        callerModule = ToolWireLimits.text(callerModule == null ? "" : callerModule.strip(), "callerModule",
                ToolWireLimits.MAX_TOOL_ID);
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
        buffer.writeUtf(callerModule);
    }

    static ToolCallRequestPacket decode(RegistryFriendlyByteBuf buffer) {
        return new ToolCallRequestPacket(buffer.readUtf(), buffer.readUtf(), buffer.readUtf(), buffer.readUtf());
    }
}
