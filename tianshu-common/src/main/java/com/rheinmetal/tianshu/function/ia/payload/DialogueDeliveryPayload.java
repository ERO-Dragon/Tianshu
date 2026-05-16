package com.rheinmetal.tianshu.function.ia.payload;

import com.rheinmetal.tianshu.function.ia.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.function.ia.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

public record DialogueDeliveryPayload(
        String sessionId,
        String requestId,
        String playerId,
        String turnId,
        String repairedText,
        String normalizedText,
        List<String> matchedHotwords,
        List<String> matchedItemIds,
        List<String> matchedEntityRefs,
        DialogueInteractionHints interactionHints,
        DialogueContextSnapshot contextSnapshot,
        long timestampMillis,
        long expireAtMillis
) implements ITianshuPayload {
    public DialogueDeliveryPayload {
        sessionId = requireText(sessionId, "sessionId");
        requestId = requireText(requestId, "requestId");
        playerId = requireText(playerId, "playerId");
        turnId = sanitize(turnId);
        repairedText = sanitize(repairedText);
        normalizedText = sanitize(normalizedText);
        matchedHotwords = copyTextList(matchedHotwords);
        matchedItemIds = copyTextList(matchedItemIds);
        matchedEntityRefs = copyTextList(matchedEntityRefs);
        interactionHints = interactionHints == null ? DialogueInteractionHints.empty() : interactionHints;
        contextSnapshot = contextSnapshot == null ? DialogueContextSnapshot.empty(playerId) : contextSnapshot;
        timestampMillis = Math.max(0L, timestampMillis);
        expireAtMillis = Math.max(0L, expireAtMillis);
    }

    public static DialogueDeliveryPayload from(String sessionId, DialogueArbitrationRequestPayload request) {
        return new DialogueDeliveryPayload(
                sessionId,
                request.requestId(),
                request.playerId(),
                request.turnId(),
                request.repairedText(),
                request.normalizedText(),
                request.matchedHotwords(),
                request.matchedItemIds(),
                request.matchedEntityRefs(),
                request.interactionHints(),
                request.contextSnapshot(),
                request.timestampMillis(),
                request.expireAtMillis()
        );
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
