package com.rheinmetal.tianshu.protocol.dialogue.context;

import java.util.List;

public record DialogueInteractionHints(String heldItemId, boolean crosshairHit, boolean interactionKeyDown, boolean sneaking, double targetDistance, List<String> tags) {
    public DialogueInteractionHints {
        heldItemId = sanitize(heldItemId);
        targetDistance = Math.max(0.0D, targetDistance);
        tags = tags == null ? List.of() : List.copyOf(tags.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList());
    }

    public static DialogueInteractionHints empty() {
        return new DialogueInteractionHints("", false, false, false, 0.0D, List.of());
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
