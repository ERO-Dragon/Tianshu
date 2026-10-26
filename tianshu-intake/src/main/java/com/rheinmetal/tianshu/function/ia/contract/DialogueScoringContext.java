package com.rheinmetal.tianshu.function.ia.contract;

import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;


import java.util.List;

public record DialogueScoringContext(
        String requestId,
        String playerId,
        String turnId,
        List<IrObjectMatch> objectMatches,
        List<IrModuleMatch> moduleMatches,
        DialogueInteractionHints interactionHints,
        DialogueContextSnapshot contextSnapshot,
        long timestampMillis,
        long expireAtMillis
) {
    public DialogueScoringContext {
        requestId = requireText(requestId, "requestId");
        playerId = requireText(playerId, "playerId");
        turnId = sanitize(turnId);
        objectMatches = objectMatches == null ? List.of() : List.copyOf(objectMatches);
        moduleMatches = moduleMatches == null ? List.of() : List.copyOf(moduleMatches);
        interactionHints = interactionHints == null ? DialogueInteractionHints.empty() : interactionHints;
        contextSnapshot = contextSnapshot == null ? DialogueContextSnapshot.empty(playerId) : contextSnapshot;
        timestampMillis = Math.max(0L, timestampMillis);
        expireAtMillis = Math.max(0L, expireAtMillis);
    }

    public boolean expiredAt(long nowMillis) {
        return expireAtMillis > 0L && expireAtMillis <= nowMillis;
    }

    private static String requireText(String value, String name) {
        String normalized = sanitize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return normalized;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
