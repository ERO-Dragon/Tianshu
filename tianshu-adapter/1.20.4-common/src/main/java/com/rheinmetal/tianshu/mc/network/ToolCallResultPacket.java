package com.rheinmetal.tianshu.mc.network;

import com.rheinmetal.tianshu.api.ModIdentity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The server's answer to one {@link ToolCallRequestPacket}. */
public record ToolCallResultPacket(String requestId, boolean ok, String valueJson, String errorCode, String message) implements CustomPacketPayload {

    public static final ResourceLocation CHANNEL = new ResourceLocation(ModIdentity.MOD_ID, "tool_call_result");

    public ToolCallResultPacket {
        requestId = ToolCallLimits.text(requestId, "requestId", ToolCallLimits.MAX_REQUEST_ID);
        valueJson = valueJson == null ? "" : valueJson;
        errorCode = errorCode == null ? "" : ToolCallLimits.text(errorCode, "errorCode", ToolCallLimits.MAX_CODE);
        message = message == null ? "" : ToolCallLimits.text(message, "message", ToolCallLimits.MAX_MESSAGE);
        if (valueJson.length() > ToolCallLimits.MAX_JSON) {
            throw new IllegalArgumentException("tool result is too large: " + valueJson.length());
        }
    }

    @Override
    public ResourceLocation id() {
        return CHANNEL;
    }

    @Override
    public void write(FriendlyByteBuf buffer) {
        encode(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(requestId);
        buffer.writeBoolean(ok);
        buffer.writeUtf(valueJson);
        buffer.writeUtf(errorCode);
        buffer.writeUtf(message);
    }

    public static ToolCallResultPacket decode(FriendlyByteBuf buffer) {
        return new ToolCallResultPacket(buffer.readUtf(), buffer.readBoolean(), buffer.readUtf(),
                buffer.readUtf(), buffer.readUtf());
    }
}
