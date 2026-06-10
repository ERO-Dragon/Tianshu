package com.rheinmetal.tianshu.function.ia.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

public record DialogueArbitrationRequestPayload(
        String requestId,
        String sourceModuleId,
        String playerId,
        String turnId,
        long sourceSessionId,
        String repairedText,
        String normalizedText,
        List<String> matchedWakeWords,
        List<String> matchedItemIds,
        List<String> matchedEntityTypeIds,
        long timestampMillis,
        long expireAtMillis
) implements ITianshuPayload {
    public DialogueArbitrationRequestPayload(String requestId, String sourceModuleId, String playerId, String turnId, long sourceSessionId, String repairedText, String normalizedText, List<String> matchedWakeWords, List<String> matchedItemIds, long timestampMillis, long expireAtMillis) {
        this(requestId, sourceModuleId, playerId, turnId, sourceSessionId, repairedText, normalizedText, matchedWakeWords, matchedItemIds, List.of(), timestampMillis, expireAtMillis);
    }

    public DialogueArbitrationRequestPayload {
        requestId = requireText(requestId, "requestId");
        sourceModuleId = requireText(sourceModuleId, "sourceModuleId");
        playerId = requireText(playerId, "playerId");
        turnId = sanitize(turnId);
        sourceSessionId = Math.max(0L, sourceSessionId);
        repairedText = sanitize(repairedText);
        normalizedText = sanitize(normalizedText);
        matchedWakeWords = copyTextList(matchedWakeWords);
        matchedItemIds = copyTextList(matchedItemIds);
        matchedEntityTypeIds = copyTextList(matchedEntityTypeIds);
        timestampMillis = Math.max(0L, timestampMillis);
        expireAtMillis = Math.max(0L, expireAtMillis);
    }

    public boolean expiredAt(long nowMillis) {
        return expireAtMillis > 0L && expireAtMillis <= nowMillis;
    }

    private static List<String> copyTextList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList());
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
