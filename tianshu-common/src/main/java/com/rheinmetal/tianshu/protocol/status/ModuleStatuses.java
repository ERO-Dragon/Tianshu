package com.rheinmetal.tianshu.protocol.status;

import java.util.Map;

public final class ModuleStatuses {
    public static final String TYPE_LIFECYCLE = "runtime.lifecycle";
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
        return keyed(moduleId, TYPE_READY, messageKey, ModuleStatusSeverity.NOTICE, READY_TTL_MILLIS);
    }

    public static ModuleStatus lifecycleStarting(String moduleId, String messageKey, long generation) {
        return lifecycle(moduleId, messageKey, ModuleStatusSeverity.INFO, generation);
    }

    public static ModuleStatus lifecycleReady(String moduleId, String messageKey, long generation) {
        return lifecycle(moduleId, messageKey, ModuleStatusSeverity.NOTICE, generation);
    }

    public static ModuleStatus lifecycleFailed(String moduleId, String messageKey, long generation) {
        return lifecycle(moduleId, messageKey, ModuleStatusSeverity.CRITICAL, generation);
    }

    public static ModuleStatus lifecycleStopped(String moduleId, String messageKey, long generation) {
        return lifecycle(moduleId, messageKey, ModuleStatusSeverity.INFO, generation);
    }

    public static ModuleStatus startingKeyed(String moduleId, String messageKey) {
        return keyed(moduleId, TYPE_STARTING, messageKey, ModuleStatusSeverity.INFO, STARTING_TTL_MILLIS);
    }

    public static ModuleStatus waitingKeyed(String moduleId, String messageKey) {
        return keyed(moduleId, TYPE_WAITING, messageKey, ModuleStatusSeverity.NOTICE, WAITING_TTL_MILLIS);
    }

    public static ModuleStatus failedKeyed(String moduleId, String messageKey) {
        return keyed(moduleId, TYPE_FAILED, messageKey, ModuleStatusSeverity.CRITICAL, FAILED_TTL_MILLIS);
    }

    private static ModuleStatus keyed(
            String moduleId,
            String statusType,
            String messageKey,
            ModuleStatusSeverity severity,
            long ttlMillis
    ) {
        return ModuleStatus.keyed(moduleId, statusType, messageKey, severity, ttlMillis, Map.of());
    }

    private static ModuleStatus lifecycle(
            String moduleId,
            String messageKey,
            ModuleStatusSeverity severity,
            long generation
    ) {
        return ModuleStatus.keyed(
                moduleId,
                TYPE_LIFECYCLE,
                messageKey,
                severity,
                0L,
                Map.of("generation", Long.toString(Math.max(0L, generation)))
        );
    }
}
