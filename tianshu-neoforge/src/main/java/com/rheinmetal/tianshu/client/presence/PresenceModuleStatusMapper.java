package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.protocol.status.ModuleStatusSeverity;

import java.util.Locale;
import java.util.Map;

public final class PresenceModuleStatusMapper {
    private static final long DEFAULT_TTL_MILLIS = 4_000L;

    public PresenceStatusSnapshot fromStatus(ModuleStatus status) {
        if (status == null || status.expired(System.currentTimeMillis())) {
            return null;
        }
        String messageKey = messageKey(status);
        String message = message(status);
        if (messageKey.isBlank() && message.isBlank()) {
            return null;
        }
        PresenceStatusType statusType = statusType(status);
        PresenceSeverity severity = severity(status.severity());
        long ttlMillis = status.ttlMillis() <= 0L ? DEFAULT_TTL_MILLIS : status.ttlMillis();
        return new PresenceStatusSnapshot(
                statusType,
                severity,
                status.moduleId(),
                messageKey,
                message,
                status.updatedAtMillis(),
                ttlMillis,
                status.tags()
        );
    }

    private String messageKey(ModuleStatus status) {
        if (status == null || status.messageKey().isBlank()) {
            return "";
        }
        return status.messageKey();
    }

    private String message(ModuleStatus status) {
        return status.fallbackMessage();
    }

    private PresenceSeverity severity(ModuleStatusSeverity severity) {
        return severity == ModuleStatusSeverity.CRITICAL ? PresenceSeverity.ERROR : PresenceSeverity.INFO;
    }

    private PresenceStatusType statusType(ModuleStatus status) {
        Map<String, String> tags = status.tags();
        String explicit = tags.getOrDefault("presenceStatusType", "");
        if (!explicit.isBlank()) {
            try {
                return PresenceStatusType.valueOf(explicit.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (status.severity() == ModuleStatusSeverity.CRITICAL) {
            return PresenceStatusType.ERROR;
        }
        String normalizedType = status.statusType().toLowerCase(Locale.ROOT);
        if (normalizedType.contains("error") || normalizedType.contains("failed")) {
            return PresenceStatusType.ERROR;
        }
        if (normalizedType.contains("ready")) {
            return PresenceStatusType.IDLE;
        }
        if (normalizedType.contains("compress") || normalizedType.contains("maintenance")) {
            return PresenceStatusType.COMPRESSING;
        }
        return PresenceStatusType.THINKING;
    }
}
