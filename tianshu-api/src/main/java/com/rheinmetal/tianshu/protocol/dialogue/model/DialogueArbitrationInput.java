package com.rheinmetal.tianshu.protocol.dialogue.model;

import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextFrame;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueArbitrationRequestPayload;

import com.rheinmetal.tianshu.protocol.payload.IrModuleMatch;
import com.rheinmetal.tianshu.protocol.payload.IrObjectMatch;

import java.util.List;
import java.util.Map;

public record DialogueArbitrationInput(
        DialogueArbitrationRequestPayload request,
        DialogueInteractionHints interactionHints,
        DialogueContextSnapshot contextSnapshot,
        /** Display names for the ids in this input, keyed by raw id; carried through to the delivery payload. */
        Map<String, String> displayNames,
        /** Named objects the utterance was matched to, straight from the repair step. */
        List<IrObjectMatch> objectMatches,
        /** Module trigger words the utterance hit, straight from the repair step. */
        List<IrModuleMatch> moduleMatches
) {
    public DialogueArbitrationInput(DialogueArbitrationRequestPayload request, DialogueInteractionHints interactionHints, DialogueContextSnapshot contextSnapshot) {
        this(request, interactionHints, contextSnapshot, Map.of(), List.of(), List.of());
    }

    public DialogueArbitrationInput {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        interactionHints = interactionHints == null ? DialogueInteractionHints.empty() : interactionHints;
        contextSnapshot = normalizeSnapshot(request.playerId(), contextSnapshot);
        displayNames = displayNames == null ? Map.of() : Map.copyOf(displayNames);
        objectMatches = objectMatches == null ? List.of() : List.copyOf(objectMatches);
        moduleMatches = moduleMatches == null ? List.of() : List.copyOf(moduleMatches);
    }

    public static DialogueArbitrationInput from(DialogueArbitrationRequestPayload request, DialogueContextFrame contextFrame) {
        DialogueContextFrame effectiveFrame = contextFrame == null ? DialogueContextFrame.empty(request.playerId()) : contextFrame;
        // The frame names what the world knows about; the request names what the repair step matched. Both are
        // needed: merging here keeps a single map travelling down to the delivery payload.
        Map<String, String> displayNames = new java.util.LinkedHashMap<>(effectiveFrame.displayNames());
        displayNames.putAll(request.displayNames());
        return new DialogueArbitrationInput(request, effectiveFrame.interactionHints(), effectiveFrame.contextSnapshot(),
                displayNames, request.objectMatches(), request.moduleMatches());
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
