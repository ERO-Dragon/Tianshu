package com.rheinmetal.tianshu.mc.network;

import com.rheinmetal.tianshu.api.ModIdentity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * The server component announcing itself and the server-side tools it provides.
 *
 * <p>An empty list still means "the component is here": the client uses the packet as the signal that server tools
 * are reachable, not the list contents.</p>
 */
public record ToolCatalogPacket(List<String> toolIds) implements CustomPacketPayload {

    public static final Type<ToolCatalogPacket> TYPE = new Type<>(
            new ResourceLocation(ModIdentity.MOD_ID, "tool_catalog"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ToolCatalogPacket> STREAM_CODEC =
            StreamCodec.ofMember(ToolCatalogPacket::encode, ToolCatalogPacket::decode);

    public ToolCatalogPacket {
        toolIds = toolIds == null ? List.of() : List.copyOf(toolIds);
        if (toolIds.size() > ToolCallLimits.MAX_TOOLS) {
            throw new IllegalArgumentException("too many server tools: " + toolIds.size());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(toolIds.size());
        for (String toolId : toolIds) {
            buffer.writeUtf(toolId);
        }
    }

    static ToolCatalogPacket decode(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > ToolCallLimits.MAX_TOOLS) {
            throw new IllegalArgumentException("invalid server tool catalog size: " + count);
        }
        List<String> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ids.add(ToolCallLimits.text(buffer.readUtf(), "toolId", ToolCallLimits.MAX_TOOL_ID));
        }
        return new ToolCatalogPacket(ids);
    }
}
