package com.rheinmetal.tianshu.client.presence;

import java.util.Map;

public record PresenceStatusSnapshot(
        PresenceStatusType statusType,
        PresenceSeverity severity,
        String sourceModuleId,
        String messageKey,
        String messageText,
        long updatedAtMillis,
        long ttlMillis,
        Map<String, String> attributes
) {
    public PresenceStatusSnapshot {
        statusType = statusType == null ? PresenceStatusType.IDLE : statusType;
        severity = severity == null ? PresenceSeverity.INFO : severity;
        sourceModuleId = clean(sourceModuleId);
        messageKey = clean(messageKey);
        messageText = clean(messageText);
        if (updatedAtMillis <= 0L) {
            updatedAtMillis = System.currentTimeMillis();
        }
        ttlMillis = Math.max(0L, ttlMillis);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static PresenceStatusSnapshot idle() {
        return new PresenceStatusSnapshot(PresenceStatusType.IDLE, PresenceSeverity.INFO, "", "presence.status.idle", "", System.currentTimeMillis(), 0L, Map.of());
    }

    public boolean expired(long nowMillis) {
        return ttlMillis > 0L && nowMillis - updatedAtMillis > ttlMillis;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
