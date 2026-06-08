package com.rheinmetal.tianshu.function.ia.context;

public record DialogueContextFrame(
        DialogueInteractionHints interactionHints,
        DialogueContextSnapshot contextSnapshot
) {
    public DialogueContextFrame {
        String playerId = contextSnapshot == null ? "" : contextSnapshot.playerId();
        interactionHints = interactionHints == null ? DialogueInteractionHints.empty() : interactionHints;
        contextSnapshot = contextSnapshot == null ? DialogueContextSnapshot.empty(playerId) : contextSnapshot;
    }

    public static DialogueContextFrame empty(String playerId) {
        return new DialogueContextFrame(DialogueInteractionHints.empty(), DialogueContextSnapshot.empty(playerId));
    }
}
