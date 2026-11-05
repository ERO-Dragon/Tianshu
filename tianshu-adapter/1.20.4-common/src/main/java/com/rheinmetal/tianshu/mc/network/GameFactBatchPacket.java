package com.rheinmetal.tianshu.mc.network;

import com.rheinmetal.tianshu.api.ModIdentity;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The packet that carries a {@link GameFactBatch}.
 *
 * <p>There is exactly one packet shape: the server buffers the facts produced during a server tick and flushes them
 * once at the end of that tick, so a burst costs one packet instead of one per fact. The list order is the
 * production order and every element keeps its own {@code occurredAtMillis}, which is what lets the receiving side
 * replay them one by one — consumers, and the presence window in particular, never see a "merged fact".</p>
 *
 * <p>Only facts about the receiving player are ever sent on this channel; the server decides that (see
 * {@code GameFactCollector}), not the client.</p>
 *
 * <p>1.20.1 predates the payload registry: a packet is a plain record plus a hand-written codec, and the channel it
 * travels on is one {@link ResourceLocation} per direction.</p>
 */
public record GameFactBatchPacket(GameFactBatch batch) implements CustomPacketPayload {

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
            new ResourceLocation(ModIdentity.MOD_ID, "game_facts");

    public GameFactBatchPacket {
        batch = batch == null ? new GameFactBatch(List.of()) : batch;
    }

    public GameFactBatchPacket(List<GameFact> facts) {
        this(new GameFactBatch(facts));
    }

    public void encode(FriendlyByteBuf buffer) {
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

    public static GameFactBatchPacket decode(FriendlyByteBuf buffer) {
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

    /**
     * The facts as world events, in the order the server produced them.
     *
     * <p>Turning a packet into local events is the loader's job: it owns the thread hop, so it owns the moment
     * the batch becomes visible. Handing back a list keeps that decision out of the wire format.</p>
     */
    public List<WorldEventPayload> toEvents() {
        return batch.toWorldEvents();
    }
}
