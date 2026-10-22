package com.rheinmetal.tianshu.protocol.dialogue.context;

import java.util.List;

public record DialogueScoringContext(
        String requestId,
        String playerId,
        String turnId,
        List<String> matchedWakeWords,
        List<String> matchedItemIds,
        List<String> matchedEntityTypeIds,
        DialogueInteractionHints interactionHints,
        DialogueContextSnapshot contextSnapshot,
        long timestampMillis,
        long expireAtMillis
) {
    public DialogueScoringContext {
        requestId = requireText(requestId, "requestId");
        playerId = requireText(playerId, "playerId");
        turnId = sanitize(turnId);
        matchedWakeWords = copyTextList(matchedWakeWords);
        matchedItemIds = copyTextList(matchedItemIds);
        matchedEntityTypeIds = copyTextList(matchedEntityTypeIds);
        interactionHints = interactionHints == null ? DialogueInteractionHints.empty() : interactionHints;
        contextSnapshot = contextSnapshot == null ? DialogueContextSnapshot.empty(playerId) : contextSnapshot;
        timestampMillis = Math.max(0L, timestampMillis);
        expireAtMillis = Math.max(0L, expireAtMillis);
    }

    public boolean expiredAt(long nowMillis) {
        return expireAtMillis > 0L && expireAtMillis <= nowMillis;
    }

    private static List<String> copyTextList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList());
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
