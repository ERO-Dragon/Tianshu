package com.rheinmetal.tianshu.function.ia.contract;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

/**
 * One turn asking to arbitrate who owns the player's next reply.
 *
 * <p>The evidence is carried as matches, not as flattened word and id lists: a named object arrives with the words
 * the player used and the registry id it resolved to, a module trigger word arrives with the word that module
 * registered. Aggregated accessors used to rebuild those pairings here; a consumer reads the match it needs instead.</p>
 */
public record DialogueArbitrationRequestPayload(
        String requestId,
        String sourceModuleId,
        String playerId,
        String turnId,
        long sourceSessionId,
        String repairedText,
        String normalizedText,
        /** Named objects the utterance was matched to, each carrying the words that were said. */
        List<IrObjectMatch> objectMatches,
        /** Module trigger words the utterance hit, each carrying the word that module registered. */
        List<IrModuleMatch> moduleMatches,
        long timestampMillis,
        long expireAtMillis
) implements ITianshuPayload {
    public DialogueArbitrationRequestPayload {
        requestId = requireText(requestId, "requestId");
        sourceModuleId = requireText(sourceModuleId, "sourceModuleId");
        playerId = requireText(playerId, "playerId");
        turnId = sanitize(turnId);
        sourceSessionId = Math.max(0L, sourceSessionId);
        repairedText = sanitize(repairedText);
        normalizedText = sanitize(normalizedText);
        objectMatches = objectMatches == null ? List.of() : List.copyOf(objectMatches);
        moduleMatches = moduleMatches == null ? List.of() : List.copyOf(moduleMatches);
        timestampMillis = Math.max(0L, timestampMillis);
        expireAtMillis = Math.max(0L, expireAtMillis);
    }

    public boolean expiredAt(long nowMillis) {
        return expireAtMillis > 0L && expireAtMillis <= nowMillis;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
