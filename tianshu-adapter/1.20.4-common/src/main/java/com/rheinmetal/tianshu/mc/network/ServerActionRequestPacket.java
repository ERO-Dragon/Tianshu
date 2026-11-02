package com.rheinmetal.tianshu.mc.network;

import com.rheinmetal.tianshu.api.ModIdentity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One action request the client sends to the optional server component.
 *
 * <p>{@code kind} is a fixed platform whitelist, not a command string: the server validates it against its own
 * switches before looking at {@code params}. The request id is the client's idempotency key — resending the same id
 * returns the first result instead of running the action twice.</p>
 *
 * <p>1.20.1 has no payload registry: the channel is one {@link ResourceLocation} and the codec is hand-written.</p>
 */
public record ServerActionRequestPacket(
        String requestId,
        String kind,
        Map<String, String> params
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
            new ResourceLocation(ModIdentity.MOD_ID, "server_action");

    public ServerActionRequestPacket {
        requestId = ServerActionLimits.text(requestId, "requestId", ServerActionLimits.MAX_ID_LENGTH);
        kind = ServerActionLimits.text(kind, "kind", ServerActionLimits.MAX_KIND_LENGTH);
        params = params == null ? Map.of() : Map.copyOf(params);
        if (params.size() > ServerActionLimits.MAX_PARAMS) {
            throw new IllegalArgumentException("too many action parameters: " + params.size());
        }
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(requestId);
        buffer.writeUtf(kind);
        buffer.writeVarInt(params.size());
        params.forEach((key, value) -> {
            buffer.writeUtf(key);
            buffer.writeUtf(value);
        });
    }

    public static ServerActionRequestPacket decode(FriendlyByteBuf buffer) {
        String requestId = ServerActionLimits.text(buffer.readUtf(), "requestId", ServerActionLimits.MAX_ID_LENGTH);
        String kind = ServerActionLimits.text(buffer.readUtf(), "kind", ServerActionLimits.MAX_KIND_LENGTH);
        int count = buffer.readVarInt();
        if (count < 0 || count > ServerActionLimits.MAX_PARAMS) {
            throw new IllegalArgumentException("invalid action parameter count: " + count);
        }
        Map<String, String> params = new LinkedHashMap<>(Math.max(0, count));
        for (int index = 0; index < count; index++) {
            String key = ServerActionLimits.text(buffer.readUtf(), "param key", ServerActionLimits.MAX_KEY_LENGTH);
            String value = ServerActionLimits.text(buffer.readUtf(), "param value", ServerActionLimits.MAX_VALUE_LENGTH);
            params.put(key, value);
        }
        return new ServerActionRequestPacket(requestId, kind, params);
    }
}
