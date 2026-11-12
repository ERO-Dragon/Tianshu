package com.rheinmetal.tianshu.mc.network;

import com.rheinmetal.tianshu.api.ModIdentity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** One tool call a client sends to the optional server component. */
public record ToolCallRequestPacket(String requestId, String toolId, String argsJson) {

    public static final ResourceLocation CHANNEL = new ResourceLocation(ModIdentity.MOD_ID, "tool_call");

    public ToolCallRequestPacket {
        requestId = ToolCallLimits.text(requestId, "requestId", ToolCallLimits.MAX_REQUEST_ID);
        toolId = ToolCallLimits.text(toolId, "toolId", ToolCallLimits.MAX_TOOL_ID);
        argsJson = argsJson == null ? "{}" : argsJson;
        if (argsJson.length() > ToolCallLimits.MAX_JSON) {
            throw new IllegalArgumentException("tool arguments are too large: " + argsJson.length());
        }
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(requestId);
        buffer.writeUtf(toolId);
        buffer.writeUtf(argsJson);
    }

    public static ToolCallRequestPacket decode(FriendlyByteBuf buffer) {
        return new ToolCallRequestPacket(buffer.readUtf(), buffer.readUtf(), buffer.readUtf());
    }
}
