package com.rheinmetal.tianshu.neoforge.network;

import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * The facts of one server tick, in production order.
 *
 * <p>This is the transport-neutral half of the batch: it owns ordering, the immutability of the list, the
 * per-fact timestamps and the split into bounded packets. {@link GameFactBatchPacket} only adds the Minecraft
 * payload wrapper, so the ordering rules are testable without a game classpath.</p>
 */
public record GameFactBatch(List<GameFact> facts) {

    /** One tick's burst is split into packets of at most this many facts; bounded so one packet stays small. */
    public static final int MAX_FACTS = 64;

    /** The largest packet this build accepts from a peer, checked before any list or map is allocated. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    public GameFactBatch {
        facts = facts == null ? List.of() : List.copyOf(facts);
    }

    /** The protocol shapes every consumer already reads, in production order. */
    public List<WorldEventPayload> toWorldEvents() {
        return facts.stream().map(GameFact::toWorldEvent).toList();
    }

    /** Splits the batch into pieces of at most {@code maxFacts}; a batch that already fits yields itself. */
    public List<GameFactBatch> split(int maxFacts) {
        if (maxFacts < 1) {
            throw new IllegalArgumentException("maxFacts must be at least 1");
        }
        if (facts.size() <= maxFacts) {
            return List.of(this);
        }
        List<GameFactBatch> pieces = new ArrayList<>(facts.size() / maxFacts + 1);
        for (int from = 0; from < facts.size(); from += maxFacts) {
            pieces.add(new GameFactBatch(facts.subList(from, Math.min(from + maxFacts, facts.size()))));
        }
        return pieces;
    }
}
