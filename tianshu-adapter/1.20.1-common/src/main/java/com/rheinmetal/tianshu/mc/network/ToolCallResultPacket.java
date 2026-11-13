package com.rheinmetal.tianshu.mc.network;

import com.rheinmetal.tianshu.api.ModIdentity;
import com.rheinmetal.tianshu.api.tool.ToolWireLimits;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** The server's answer to one {@link ToolCallRequestPacket}. */
public record ToolCallResultPacket(String requestId, boolean ok, String valueJson, String errorCode, String message) {

    public static final ResourceLocation CHANNEL = new ResourceLocation(ModIdentity.MOD_ID, "tool_call_result");

    public ToolCallResultPacket {
        requestId = ToolWireLimits.text(requestId, "requestId", ToolWireLimits.MAX_REQUEST_ID);
        ToolWireLimits.Fitted fitted = ToolWireLimits.fitResult(ok, valueJson, errorCode, message);
        ok = fitted.ok();
        valueJson = fitted.valueJson();
        errorCode = fitted.errorCode();
        message = fitted.message();
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
