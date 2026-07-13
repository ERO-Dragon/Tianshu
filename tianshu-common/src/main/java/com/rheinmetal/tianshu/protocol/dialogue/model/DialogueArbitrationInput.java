package com.rheinmetal.tianshu.protocol.dialogue.model;

import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextFrame;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueArbitrationRequestPayload;

import java.util.List;
import java.util.Map;

public record DialogueArbitrationInput(
        DialogueArbitrationRequestPayload request,
        DialogueInteractionHints interactionHints,
        DialogueContextSnapshot contextSnapshot
) {
    public DialogueArbitrationInput {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        interactionHints = interactionHints == null ? DialogueInteractionHints.empty() : interactionHints;
        contextSnapshot = normalizeSnapshot(request.playerId(), contextSnapshot);
    }

    public static DialogueArbitrationInput from(DialogueArbitrationRequestPayload request, DialogueContextFrame contextFrame) {
        DialogueContextFrame effectiveFrame = contextFrame == null ? DialogueContextFrame.empty(request.playerId()) : contextFrame;
        return new DialogueArbitrationInput(request, effectiveFrame.interactionHints(), effectiveFrame.contextSnapshot());
    }

    public String requestId() {
        return request.requestId();
    }

    public String playerId() {
        return request.playerId();
    }

    public String turnId() {
        return request.turnId();
    }

    public String repairedText() {
        return request.repairedText();
    }

    public String normalizedText() {
        return request.normalizedText();
    }

    public List<String> matchedWakeWords() {
        return request.matchedWakeWords();
    }

    public List<String> matchedItemIds() {
        return request.matchedItemIds();
    }

    public List<String> matchedEntityTypeIds() {
        return request.matchedEntityTypeIds();
    }

    public long timestampMillis() {
        return request.timestampMillis();
    }

    public long expireAtMillis() {
        return request.expireAtMillis();
    }

    private static DialogueContextSnapshot normalizeSnapshot(String playerId, DialogueContextSnapshot snapshot) {
        if (snapshot == null) {
            return DialogueContextSnapshot.empty(playerId);
        }
        if (!snapshot.playerId().isBlank()) {
            return snapshot;
        }
        return new DialogueContextSnapshot(
                playerId,
                snapshot.dimensionId(),
                snapshot.entityRefs(),
                snapshot.equippedItemIds(),
                snapshot.facts() == null ? Map.of() : snapshot.facts()
        );
    }
}
