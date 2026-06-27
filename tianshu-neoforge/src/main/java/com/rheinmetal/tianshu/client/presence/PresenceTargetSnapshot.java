package com.rheinmetal.tianshu.client.presence;

public record PresenceTargetSnapshot(
        String entityId,
        String entityTypeId,
        String displayName,
        double distance,
        boolean crosshairTarget
) {
    public PresenceTargetSnapshot {
        entityId = clean(entityId);
        entityTypeId = clean(entityTypeId);
        displayName = clean(displayName);
        distance = Math.max(0.0D, distance);
    }

    public static PresenceTargetSnapshot empty() {
        return new PresenceTargetSnapshot("", "", "", 0.0D, false);
    }

    public boolean present() {
        return !entityId.isBlank() || !entityTypeId.isBlank();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
