package com.rheinmetal.tianshu.function.ia.payload;

import com.rheinmetal.tianshu.function.ia.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.function.ia.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.function.ia.model.DialogueArbitrationInput;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

public record DialogueDeliveryPayload(
        String sessionId,
        String requestId,
        String playerId,
        String turnId,
        String repairedText,
        String normalizedText,
        List<String> matchedWakeWords,
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
        matchedWakeWords = copyTextList(matchedWakeWords);
        matchedItemIds = copyTextList(matchedItemIds);
        matchedEntityRefs = copyTextList(matchedEntityRefs);
        interactionHints = interactionHints == null ? DialogueInteractionHints.empty() : interactionHints;
        contextSnapshot = contextSnapshot == null ? DialogueContextSnapshot.empty(playerId) : contextSnapshot;
        timestampMillis = Math.max(0L, timestampMillis);
        expireAtMillis = Math.max(0L, expireAtMillis);
    }

    public static DialogueDeliveryPayload from(String sessionId, DialogueArbitrationInput input) {
        return new DialogueDeliveryPayload(
                sessionId,
                input.requestId(),
                input.playerId(),
                input.turnId(),
                input.repairedText(),
                input.normalizedText(),
                input.matchedWakeWords(),
                input.matchedItemIds(),
                input.contextSnapshot().entityRefs().stream()
                        .map(ref -> ref.entityTypeId())
                        .filter(value -> value != null && !value.isBlank())
                        .distinct()
                        .toList(),
                input.interactionHints(),
                input.contextSnapshot(),
                input.timestampMillis(),
                input.expireAtMillis()
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
