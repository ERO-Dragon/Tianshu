package com.rheinmetal.tianshu.mc.network;

import com.rheinmetal.tianshu.api.ModIdentity;
import com.rheinmetal.tianshu.api.tool.ToolWireLimits;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** The server's answer to one {@link ToolCallRequestPacket}: a JSON result, or a code and a message. */
public record ToolCallResultPacket(String requestId, boolean ok, String valueJson, String errorCode, String message)
        implements CustomPacketPayload {

    public static final Type<ToolCallResultPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(ModIdentity.MOD_ID, "tool_call_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ToolCallResultPacket> STREAM_CODEC =
            StreamCodec.ofMember(ToolCallResultPacket::encode, ToolCallResultPacket::decode);

    public ToolCallResultPacket {
        requestId = ToolWireLimits.text(requestId, "requestId", ToolWireLimits.MAX_REQUEST_ID);
        ToolWireLimits.Fitted fitted = ToolWireLimits.fitResult(ok, valueJson, errorCode, message);
        ok = fitted.ok();
        valueJson = fitted.valueJson();
        errorCode = fitted.errorCode();
        message = fitted.message();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(requestId);
        buffer.writeBoolean(ok);
        buffer.writeUtf(valueJson);
        buffer.writeUtf(errorCode);
        buffer.writeUtf(message);
    }

    static ToolCallResultPacket decode(RegistryFriendlyByteBuf buffer) {
        return new ToolCallResultPacket(buffer.readUtf(), buffer.readBoolean(), buffer.readUtf(),
                buffer.readUtf(), buffer.readUtf());
    }
}
