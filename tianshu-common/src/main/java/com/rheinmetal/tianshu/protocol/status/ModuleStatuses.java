package com.rheinmetal.tianshu.protocol.status;

import java.util.Map;

public final class ModuleStatuses {
    public static final String TYPE_READY = "runtime.ready";
    public static final String TYPE_STARTING = "runtime.starting";
    public static final String TYPE_FAILED = "runtime.failed";
    public static final String TYPE_WAITING = "runtime.waiting";

    private static final long READY_TTL_MILLIS = 4_000L;
    private static final long STARTING_TTL_MILLIS = 2_000L;
    private static final long FAILED_TTL_MILLIS = 8_000L;
    private static final long WAITING_TTL_MILLIS = 2_500L;

    private ModuleStatuses() {
    }

    public static ModuleStatus ready(String moduleId, String message) {
        return ModuleStatus.of(moduleId, TYPE_READY, message, ModuleStatusSeverity.NOTICE, READY_TTL_MILLIS, Map.of(
                "presenceStatusType", "IDLE"
        ));
    }

    public static ModuleStatus readyKeyed(String moduleId, String messageKey, String fallbackMessage) {
        return keyed(moduleId, TYPE_READY, messageKey, fallbackMessage, ModuleStatusSeverity.NOTICE, READY_TTL_MILLIS, "IDLE");
    }

    public static ModuleStatus starting(String moduleId, String message) {
        return ModuleStatus.of(moduleId, TYPE_STARTING, message, ModuleStatusSeverity.INFO, STARTING_TTL_MILLIS, Map.of(
                "presenceStatusType", "THINKING"
        ));
    }

    public static ModuleStatus startingKeyed(String moduleId, String messageKey, String fallbackMessage) {
        return keyed(moduleId, TYPE_STARTING, messageKey, fallbackMessage, ModuleStatusSeverity.INFO, STARTING_TTL_MILLIS, "THINKING");
    }

    public static ModuleStatus waiting(String moduleId, String message) {
        return ModuleStatus.of(moduleId, TYPE_WAITING, message, ModuleStatusSeverity.NOTICE, WAITING_TTL_MILLIS, Map.of(
                "presenceStatusType", "THINKING"
        ));
    }

    public static ModuleStatus waitingKeyed(String moduleId, String messageKey, String fallbackMessage) {
        return keyed(moduleId, TYPE_WAITING, messageKey, fallbackMessage, ModuleStatusSeverity.NOTICE, WAITING_TTL_MILLIS, "THINKING");
    }

    public static ModuleStatus failed(String moduleId, String message) {
        return ModuleStatus.of(moduleId, TYPE_FAILED, message, ModuleStatusSeverity.CRITICAL, FAILED_TTL_MILLIS, Map.of(
                "presenceStatusType", "ERROR"
        ));
    }

    public static ModuleStatus failedKeyed(String moduleId, String messageKey, String fallbackMessage) {
        return keyed(moduleId, TYPE_FAILED, messageKey, fallbackMessage, ModuleStatusSeverity.CRITICAL, FAILED_TTL_MILLIS, "ERROR");
    }

    private static ModuleStatus keyed(
            String moduleId,
            String statusType,
            String messageKey,
            String fallbackMessage,
            ModuleStatusSeverity severity,
            long ttlMillis,
            String presenceStatusType
    ) {
        return ModuleStatus.keyed(moduleId, statusType, messageKey, fallbackMessage, severity, ttlMillis, Map.of(
                "presenceStatusType", presenceStatusType
        ));
    }
}
