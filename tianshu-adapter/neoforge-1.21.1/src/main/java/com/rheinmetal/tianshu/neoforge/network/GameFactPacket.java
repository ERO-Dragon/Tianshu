package com.rheinmetal.tianshu.neoforge.network;

import com.rheinmetal.tianshu.neoforge.TianshuNeoForge;
import com.rheinmetal.tianshu.neoforge.event.NeoForgeWorldEventHooks;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;
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
 * into a {@link WorldEventPayload}, which is the shape every consumer already understands. A fact that no
 * client knows how to consume is dropped there — the client is allowed to be older than the server.</p>
 *
 * <p>Only facts about the receiving player are ever sent on this channel; the server decides that (see
 * {@code GameFactCollector}), not the client.</p>
 */
public record GameFactPacket(
        String factId,
        String dimensionId,
        long occurredAtMillis,
        Map<String, String> values
) implements CustomPacketPayload {

    public static final Type<GameFactPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TianshuNeoForge.MOD_ID, "game_fact"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GameFactPacket> STREAM_CODEC =
            StreamCodec.ofMember(GameFactPacket::encode, GameFactPacket::decode);

    public GameFactPacket {
        factId = factId == null ? "" : factId.trim();
        dimensionId = dimensionId == null ? "" : dimensionId.trim();
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** The protocol shape every consumer already reads; no client-side class is touched here. */
    public WorldEventPayload toWorldEvent() {
        return new WorldEventPayload(factId, "", dimensionId, values, occurredAtMillis);
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

    static GameFactPacket decode(RegistryFriendlyByteBuf buffer) {
        String factId = buffer.readUtf();
        String dimensionId = buffer.readUtf();
        long occurredAtMillis = buffer.readLong();
        int size = buffer.readVarInt();
        Map<String, String> values = new LinkedHashMap<>(Math.max(0, size));
        for (int index = 0; index < size; index++) {
            values.put(buffer.readUtf(), buffer.readUtf());
        }
        return new GameFactPacket(factId, dimensionId, occurredAtMillis, values);
    }

    /** Runs on the client thread: the world-event entry is a client-side object. */
    static void handle(GameFactPacket packet, IPayloadContext context) {
        WorldEventPayload event = packet.toWorldEvent();
        context.enqueueWork(() -> NeoForgeWorldEventHooks.publish(event));
    }
}