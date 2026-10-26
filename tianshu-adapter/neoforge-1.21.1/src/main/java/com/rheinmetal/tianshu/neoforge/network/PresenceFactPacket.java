package com.rheinmetal.tianshu.neoforge.network;

import com.rheinmetal.tianshu.neoforge.TianshuNeoForge;
import com.rheinmetal.tianshu.neoforge.event.NeoForgePresenceHooks;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One authoritative fact travelling from the optional server component to a client.
 *
 * <p>The wire type is a platform concern and lives here, not in the protocol api: the receiving side turns it straight
 * into a {@link PresenceWorldEventPayload}, which is the shape every consumer already understands. A fact that no
 * client knows how to consume is dropped there — the client is allowed to be older than the server.</p>
 *
 * <p>Only facts about the receiving player are ever sent on this channel; the server decides that (see
 * {@code PresenceServerCollector}), not the client.</p>
 */
public record PresenceFactPacket(
        String factId,
        String dimensionId,
        long occurredAtMillis,
        Map<String, String> values
) implements CustomPacketPayload {

    public static final Type<PresenceFactPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TianshuNeoForge.MOD_ID, "presence_fact"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PresenceFactPacket> STREAM_CODEC =
            StreamCodec.ofMember(PresenceFactPacket::encode, PresenceFactPacket::decode);

    public PresenceFactPacket {
        factId = factId == null ? "" : factId.trim();
        dimensionId = dimensionId == null ? "" : dimensionId.trim();
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** The protocol shape every consumer already reads; no client-side class is touched here. */
    public PresenceWorldEventPayload toWorldEvent() {
        return new PresenceWorldEventPayload(null, factId, "", dimensionId, occurredAtMillis, values);
    }

    void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(factId);
        buffer.writeUtf(dimensionId);
        buffer.writeLong(occurredAtMillis);
        buffer.writeVarInt(values.size());
        values.forEach((key, value) -> {
            buffer.writeUtf(key);
            buffer.writeUtf(value);
        });
    }

    static PresenceFactPacket decode(RegistryFriendlyByteBuf buffer) {
        String factId = buffer.readUtf();
        String dimensionId = buffer.readUtf();
        long occurredAtMillis = buffer.readLong();
        int size = buffer.readVarInt();
        Map<String, String> values = new LinkedHashMap<>(Math.max(0, size));
        for (int index = 0; index < size; index++) {
            values.put(buffer.readUtf(), buffer.readUtf());
        }
        return new PresenceFactPacket(factId, dimensionId, occurredAtMillis, values);
    }

    /** Runs on the client thread: the presence runtime is a client-side object. */
    static void handle(PresenceFactPacket packet, IPayloadContext context) {
        PresenceWorldEventPayload event = packet.toWorldEvent();
        context.enqueueWork(() -> NeoForgePresenceHooks.recordServerFact(event));
    }
}