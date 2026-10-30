package com.rheinmetal.tianshu.neoforge.network;

import com.rheinmetal.tianshu.neoforge.TianshuNeoForge;
import com.rheinmetal.tianshu.neoforge.event.NeoForgeWorldEventHooks;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Minecraft payload that carries a {@link GameFactBatch}.
 *
 * <p>There is exactly one packet shape: the server buffers the facts produced during a server tick and flushes them
 * once at the end of that tick, so a burst costs one packet instead of one per fact. The list order is the
 * production order and every element keeps its own {@code occurredAtMillis}, which is what lets the receiving side
 * replay them one by one — consumers, and the presence window in particular, never see a "merged fact".</p>
 *
 * <p>Only facts about the receiving player are ever sent on this channel; the server decides that (see
 * {@code GameFactCollector}), not the client.</p>
 */
public record GameFactBatchPacket(GameFactBatch batch) implements CustomPacketPayload {

    public static final Type<GameFactBatchPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TianshuNeoForge.MOD_ID, "game_facts"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GameFactBatchPacket> STREAM_CODEC =
            StreamCodec.ofMember(GameFactBatchPacket::encode, GameFactBatchPacket::decode);

    public GameFactBatchPacket {
        batch = batch == null ? new GameFactBatch(List.of()) : batch;
    }

    public GameFactBatchPacket(List<GameFact> facts) {
        this(new GameFactBatch(facts));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    void encode(RegistryFriendlyByteBuf buffer) {
        List<GameFact> facts = batch.facts();
        buffer.writeVarInt(facts.size());
        for (GameFact fact : facts) {
            buffer.writeUtf(fact.factId());
            buffer.writeUtf(fact.dimensionId());
            buffer.writeLong(fact.occurredAtMillis());
            buffer.writeVarInt(fact.values().size());
            fact.values().forEach((key, value) -> {
                buffer.writeUtf(key);
                buffer.writeUtf(value);
            });
        }
    }

    static GameFactBatchPacket decode(RegistryFriendlyByteBuf buffer) {
        // Everything below is declared by the peer: bound the total size and every count before allocating.
        GameFactLimits.requirePacketBytes(buffer.readableBytes());
        int factCount = GameFactLimits.requireFactCount(buffer.readVarInt());
        List<GameFact> facts = new ArrayList<>(factCount);
        for (int factIndex = 0; factIndex < factCount; factIndex++) {
            String factId = GameFactLimits.requireText(buffer.readUtf(), "factId");
            String dimensionId = GameFactLimits.requireText(buffer.readUtf(), "dimensionId");
            long occurredAtMillis = buffer.readLong();
            int size = GameFactLimits.requireValueCount(buffer.readVarInt());
            Map<String, String> values = new LinkedHashMap<>(Math.max(0, size));
            for (int index = 0; index < size; index++) {
                String key = GameFactLimits.requireText(buffer.readUtf(), "key");
                String value = GameFactLimits.requireText(buffer.readUtf(), "value");
                values.put(key, value);
            }
            facts.add(new GameFact(factId, dimensionId, occurredAtMillis, values));
        }
        return new GameFactBatchPacket(facts);
    }

    /** Runs on the client thread: the world-event entry is a client-side object. */
    static void handle(GameFactBatchPacket packet, IPayloadContext context) {
        List<WorldEventPayload> events = packet.batch.toWorldEvents();
        if (events.isEmpty()) {
            return;
        }
        // One enqueue keeps the batch atomic with respect to other client work: consumers see the facts in the
        // order the server produced them, not interleaved with another packet.
        context.enqueueWork(() -> events.forEach(NeoForgeWorldEventHooks::publish));
    }
}
