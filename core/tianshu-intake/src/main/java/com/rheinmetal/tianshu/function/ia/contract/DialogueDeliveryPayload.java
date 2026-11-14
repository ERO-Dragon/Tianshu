package com.rheinmetal.tianshu.function.ia.contract;

import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueEntityRef;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.function.ia.model.DialogueArbitrationInput;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record DialogueDeliveryPayload(
        String sessionId,
        String requestId,
        String playerId,
        String turnId,
        String repairedText,
        String normalizedText,
        List<DialogueEntityRef> matchedEntityRefs,
        DialogueInteractionHints interactionHints,
        DialogueContextSnapshot contextSnapshot,
        long timestampMillis,
        long expireAtMillis,
        /**
         * Display names for the ids the world side resolved, keyed by the raw id (dimension, held item, entity type).
         *
         * <p>One map instead of a parallel list per id kind: a consumer that renders an id asks this map first and
         * falls back to a readable form of the id when the producer had no localized name at hand. A missing key is
         * the normal case, not an error — the model must never be shown a raw {@code namespace:path} because the
         * name was unavailable.</p>
         *
         * <p>Names of the objects the player mentioned are not here: they travel inside
         * {@link #objectMatches()}, which is where the words actually said come from.</p>
         */
        Map<String, String> displayNames,
        /** Named objects the utterance was matched to, each carrying the words that were actually said. */
        List<IrObjectMatch> objectMatches,
        /** Module trigger words the utterance hit, each carrying the word that module registered. */
        List<IrModuleMatch> moduleMatches
) implements ITianshuPayload {
    /** Payload without display names or matches: every consumer then falls back to a readable form of each id. */
    public DialogueDeliveryPayload(String sessionId, String requestId, String playerId, String turnId, String repairedText, String normalizedText, List<DialogueEntityRef> matchedEntityRefs, DialogueInteractionHints interactionHints, DialogueContextSnapshot contextSnapshot, long timestampMillis, long expireAtMillis) {
        this(sessionId, requestId, playerId, turnId, repairedText, normalizedText, matchedEntityRefs, interactionHints, contextSnapshot, timestampMillis, expireAtMillis, Map.of(), List.of(), List.of());
    }

    public DialogueDeliveryPayload {
        sessionId = requireText(sessionId, "sessionId");
        requestId = requireText(requestId, "requestId");
        playerId = requireText(playerId, "playerId");
        turnId = sanitize(turnId);
        repairedText = sanitize(repairedText);
        normalizedText = sanitize(normalizedText);
        matchedEntityRefs = copyEntityRefs(matchedEntityRefs);
        interactionHints = interactionHints == null ? DialogueInteractionHints.empty() : interactionHints;
        contextSnapshot = contextSnapshot == null ? DialogueContextSnapshot.empty(playerId) : contextSnapshot;
        timestampMillis = Math.max(0L, timestampMillis);
        expireAtMillis = Math.max(0L, expireAtMillis);
        displayNames = copyDisplayNames(displayNames);
        objectMatches = objectMatches == null ? List.of() : List.copyOf(objectMatches);
        moduleMatches = moduleMatches == null ? List.of() : List.copyOf(moduleMatches);
    }

    public static DialogueDeliveryPayload from(String sessionId, DialogueArbitrationInput input) {
        return new DialogueDeliveryPayload(
                sessionId,
                input.requestId(),
                input.playerId(),
                input.turnId(),
                input.repairedText(),
                input.normalizedText(),
                input.contextSnapshot().entityRefs(),
                input.interactionHints(),
                input.contextSnapshot(),
                input.timestampMillis(),
                input.expireAtMillis(),
                input.displayNames(),
                input.objectMatches(),
                input.moduleMatches()
        );
    }

    /** Id to display name; blank keys and values are dropped so "absent" stays distinguishable from "empty". */
    private static Map<String, String> copyDisplayNames(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                return;
            }
            copied.putIfAbsent(key.trim(), value.trim());
        });
        return Map.copyOf(copied);
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
