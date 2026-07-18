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

    public static ModuleStatus readyKeyed(String moduleId, String messageKey) {
        return keyed(moduleId, TYPE_READY, messageKey, ModuleStatusSeverity.NOTICE, READY_TTL_MILLIS, "IDLE");
    }

    public static ModuleStatus startingKeyed(String moduleId, String messageKey) {
        return keyed(moduleId, TYPE_STARTING, messageKey, ModuleStatusSeverity.INFO, STARTING_TTL_MILLIS, "THINKING");
    }

    public static ModuleStatus waitingKeyed(String moduleId, String messageKey) {
        return keyed(moduleId, TYPE_WAITING, messageKey, ModuleStatusSeverity.NOTICE, WAITING_TTL_MILLIS, "THINKING");
    }

    public static ModuleStatus failedKeyed(String moduleId, String messageKey) {
        return keyed(moduleId, TYPE_FAILED, messageKey, ModuleStatusSeverity.CRITICAL, FAILED_TTL_MILLIS, "ERROR");
    }

    private static ModuleStatus keyed(
            String moduleId,
            String statusType,
            String messageKey,
            ModuleStatusSeverity severity,
            long ttlMillis,
            String presenceStatusType
    ) {
        return ModuleStatus.keyed(moduleId, statusType, messageKey, severity, ttlMillis, Map.of(
                "presenceStatusType", presenceStatusType
        ));
    }
}
