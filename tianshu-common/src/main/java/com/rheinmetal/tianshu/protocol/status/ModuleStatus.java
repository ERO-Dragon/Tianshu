package com.rheinmetal.tianshu.protocol.status;

import java.util.Map;

public record ModuleStatus(
        String moduleId,
        String statusType,
        String messageKey,
        String fallbackMessage,
        ModuleStatusSeverity severity,
        long updatedAtMillis,
        long ttlMillis,
        Map<String, String> tags
) {
    public ModuleStatus {
        moduleId = requireText(moduleId, "moduleId");
        statusType = requireText(statusType, "statusType");
        messageKey = sanitize(messageKey);
        fallbackMessage = sanitize(fallbackMessage);
        severity = severity == null ? ModuleStatusSeverity.INFO : severity;
        if (updatedAtMillis <= 0L) updatedAtMillis = System.currentTimeMillis();
        ttlMillis = Math.max(0L, ttlMillis);
        tags = tags == null || tags.isEmpty() ? Map.of() : Map.copyOf(tags);
    }

    public boolean expired(long nowMillis) {
        return ttlMillis > 0L && nowMillis > updatedAtMillis + ttlMillis;
    }

    public static ModuleStatus of(
            String moduleId,
            String statusType,
            String fallbackMessage,
            ModuleStatusSeverity severity,
            long ttlMillis,
            Map<String, String> tags
    ) {
        return new ModuleStatus(
                moduleId,
                statusType,
                "",
                fallbackMessage,
                severity,
                System.currentTimeMillis(),
                ttlMillis,
                tags
        );
    }

    public static ModuleStatus keyed(
            String moduleId,
            String statusType,
            String messageKey,
            String fallbackMessage,
            ModuleStatusSeverity severity,
            long ttlMillis,
            Map<String, String> tags
    ) {
        return new ModuleStatus(
                moduleId,
                statusType,
                messageKey,
                fallbackMessage,
                severity,
                System.currentTimeMillis(),
                ttlMillis,
                tags
        );
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
