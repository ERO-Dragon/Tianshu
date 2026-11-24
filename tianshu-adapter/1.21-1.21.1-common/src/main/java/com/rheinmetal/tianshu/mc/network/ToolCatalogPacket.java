package com.rheinmetal.tianshu.mc.network;

import com.rheinmetal.tianshu.api.ModIdentity;
import com.rheinmetal.tianshu.api.tool.ToolCatalogEntry;
import com.rheinmetal.tianshu.api.tool.ToolWireLimits;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * The server component announcing itself and the server-side tools it provides, with their server-side switch.
 *
 * <p>An empty list still means "the component is here": the client uses the packet as the signal that server tools
 * are reachable, not the list contents.</p>
 *
 * <p><b>The switch travels with the entry.</b> Sending ids alone left the client to apply its own default, and the
 * world-writing tools default to off — so an operator who enabled one on the server watched the client answer
 * {@code DISABLED} without ever sending the call. The server owns that state; the client mirrors it.</p>
 */
public record ToolCatalogPacket(List<ToolCatalogEntry> tools) implements CustomPacketPayload {

    public static final Type<ToolCatalogPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "tool_catalog"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ToolCatalogPacket> STREAM_CODEC =
            StreamCodec.ofMember(ToolCatalogPacket::encode, ToolCatalogPacket::decode);

    public ToolCatalogPacket {
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (tools.size() > ToolWireLimits.MAX_TOOLS) {
            throw new IllegalArgumentException("too many server tools: " + tools.size());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(tools.size());
        for (ToolCatalogEntry entry : tools) {
            buffer.writeUtf(entry.toolId());
            buffer.writeBoolean(entry.enabled());
        }
    }

    static ToolCatalogPacket decode(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > ToolWireLimits.MAX_TOOLS) {
            throw new IllegalArgumentException("invalid server tool catalog size: " + count);
        }
        List<ToolCatalogEntry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String id = ToolWireLimits.text(buffer.readUtf(), "toolId", ToolWireLimits.MAX_TOOL_ID);
            entries.add(new ToolCatalogEntry(id, buffer.readBoolean()));
        }
        return new ToolCatalogPacket(entries);
    }
}