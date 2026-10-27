package com.rheinmetal.tianshu.protocol.dialogue.context;

public record DialogueInteractionHints(
        String heldItemId,
        boolean crosshairHit,
        boolean interactionKeyDown,
        boolean sneaking,
        double targetDistance
) {
    public DialogueInteractionHints {
        heldItemId = sanitize(heldItemId);
        targetDistance = Math.max(0.0D, targetDistance);
    }

    public static DialogueInteractionHints empty() {
        return new DialogueInteractionHints("", false, false, false, 0.0D);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
