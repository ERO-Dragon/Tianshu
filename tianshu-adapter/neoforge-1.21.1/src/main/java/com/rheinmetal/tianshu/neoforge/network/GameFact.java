package com.rheinmetal.tianshu.neoforge.network;

import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;

import java.util.Map;

/**
 * One authoritative fact inside a {@link GameFactBatchPacket}.
 *
 * <p>The wire type is a platform concern and lives here, not in the protocol api: the receiving side turns it
 * straight into a {@link WorldEventPayload}, which is the shape every consumer already understands. A fact that no
 * client knows how to consume is dropped there — the client is allowed to be older than the server.</p>
 */
public record GameFact(
        String factId,
        String dimensionId,
        long occurredAtMillis,
        Map<String, String> values
) {
    public GameFact {
        factId = factId == null ? "" : factId.trim();
        dimensionId = dimensionId == null ? "" : dimensionId.trim();
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    /** The protocol shape every consumer already reads; no client-side class is touched here. */
    public WorldEventPayload toWorldEvent() {
        return new WorldEventPayload(factId, "", dimensionId, values, occurredAtMillis);
    }
}
