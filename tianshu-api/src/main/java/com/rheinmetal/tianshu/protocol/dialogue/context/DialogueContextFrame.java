package com.rheinmetal.tianshu.protocol.dialogue.context;

import java.util.Map;

public record DialogueContextFrame(
        DialogueInteractionHints interactionHints,
        DialogueContextSnapshot contextSnapshot,
        /**
         * Display names for the ids this frame carries, keyed by raw id; empty when the producer had none.
         *
         * <p>This is the only place a localized name can enter the dialogue path: the presence snapshot holds ids
         * plus, for some facts, a companion display name, and the frame is where those two are paired up.</p>
         */
        Map<String, String> displayNames
) {
    public DialogueContextFrame(DialogueInteractionHints interactionHints, DialogueContextSnapshot contextSnapshot) {
        this(interactionHints, contextSnapshot, Map.of());
    }

    public DialogueContextFrame {
        String playerId = contextSnapshot == null ? "" : contextSnapshot.playerId();
        interactionHints = interactionHints == null ? DialogueInteractionHints.empty() : interactionHints;
        contextSnapshot = contextSnapshot == null ? DialogueContextSnapshot.empty(playerId) : contextSnapshot;
        displayNames = displayNames == null ? Map.of() : Map.copyOf(displayNames);
    }

    public static DialogueContextFrame empty(String playerId) {
        return new DialogueContextFrame(DialogueInteractionHints.empty(), DialogueContextSnapshot.empty(playerId), Map.of());
    }
}
