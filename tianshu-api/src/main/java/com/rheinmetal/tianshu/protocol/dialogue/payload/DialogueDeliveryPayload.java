package com.rheinmetal.tianshu.protocol.dialogue.payload;

import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueEntityRef;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueArbitrationInput;
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
        List<String> matchedEntityTypeIds,
        List<DialogueEntityRef> matchedEntityRefs,
        DialogueInteractionHints interactionHints,
        DialogueContextSnapshot contextSnapshot,
        long timestampMillis,
        long expireAtMillis
) implements ITianshuPayload {
    public DialogueDeliveryPayload(String sessionId, String requestId, String playerId, String turnId, String repairedText, String normalizedText, List<String> matchedWakeWords, List<String> matchedItemIds, List<DialogueEntityRef> matchedEntityRefs, DialogueInteractionHints interactionHints, DialogueContextSnapshot contextSnapshot, long timestampMillis, long expireAtMillis) {
        this(sessionId, requestId, playerId, turnId, repairedText, normalizedText, matchedWakeWords, matchedItemIds, List.of(), matchedEntityRefs, interactionHints, contextSnapshot, timestampMillis, expireAtMillis);
    }

    public DialogueDeliveryPayload {
        sessionId = requireText(sessionId, "sessionId");
        requestId = requireText(requestId, "requestId");
        playerId = requireText(playerId, "playerId");
        turnId = sanitize(turnId);
        repairedText = sanitize(repairedText);
        normalizedText = sanitize(normalizedText);
        matchedWakeWords = copyTextList(matchedWakeWords);
        matchedItemIds = copyTextList(matchedItemIds);
        matchedEntityTypeIds = copyTextList(matchedEntityTypeIds);
        matchedEntityRefs = copyEntityRefs(matchedEntityRefs);
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
                input.matchedEntityTypeIds(),
                input.contextSnapshot().entityRefs(),
                input.interactionHints(),
                input.contextSnapshot(),
                input.timestampMillis(),
                input.expireAtMillis()
        );
    }

    private static List<String> copyTextList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList());
    }

    private static List<DialogueEntityRef> copyEntityRefs(List<DialogueEntityRef> values) {
        return values == null ? List.of() : List.copyOf(values.stream()
                .filter(ref -> ref != null && !ref.entityId().isBlank() && !ref.entityTypeId().isBlank())
                .toList());
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
