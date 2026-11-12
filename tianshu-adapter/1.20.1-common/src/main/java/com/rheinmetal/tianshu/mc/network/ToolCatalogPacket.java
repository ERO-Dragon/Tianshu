package com.rheinmetal.tianshu.mc.network;

import com.rheinmetal.tianshu.api.ModIdentity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** The server component announcing itself and the server-side tools it provides. */
public record ToolCatalogPacket(List<String> toolIds) {

    public static final ResourceLocation CHANNEL = new ResourceLocation(ModIdentity.MOD_ID, "tool_catalog");

    public ToolCatalogPacket {
        toolIds = toolIds == null ? List.of() : List.copyOf(toolIds);
        if (toolIds.size() > ToolCallLimits.MAX_TOOLS) {
            throw new IllegalArgumentException("too many server tools: " + toolIds.size());
        }
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(toolIds.size());
        for (String toolId : toolIds) {
            buffer.writeUtf(toolId);
        }
    }

    public static ToolCatalogPacket decode(FriendlyByteBuf buffer) {
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
