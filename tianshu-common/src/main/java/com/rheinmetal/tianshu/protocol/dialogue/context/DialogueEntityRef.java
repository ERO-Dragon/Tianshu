package com.rheinmetal.tianshu.protocol.dialogue.context;

public record DialogueEntityRef(String entityId, String entityTypeId, String displayName, double distance, boolean crosshairTarget) {
    public DialogueEntityRef {
        entityId = sanitize(entityId);
        entityTypeId = sanitize(entityTypeId);
        displayName = sanitize(displayName);
        distance = Math.max(0.0D, distance);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
