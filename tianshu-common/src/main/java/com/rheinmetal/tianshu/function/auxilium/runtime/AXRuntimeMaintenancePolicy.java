package com.rheinmetal.tianshu.function.auxilium.runtime;

public record AXRuntimeMaintenancePolicy(
        long runtimeFactRefreshIntervalMillis,
        long memoryConsolidationIntervalMillis
) {
    public static final AXRuntimeMaintenancePolicy DEFAULT = new AXRuntimeMaintenancePolicy(
            0L,
            300_000L
    );

    public AXRuntimeMaintenancePolicy {
        runtimeFactRefreshIntervalMillis = Math.max(0L, runtimeFactRefreshIntervalMillis);
        memoryConsolidationIntervalMillis = Math.max(0L, memoryConsolidationIntervalMillis);
    }

    public boolean shouldRefreshRuntimeFacts(long lastRunAt, long now) {
        return shouldRun(runtimeFactRefreshIntervalMillis, lastRunAt, now);
    }

    public boolean shouldConsolidateMemory(long lastRunAt, long now) {
        return shouldRun(memoryConsolidationIntervalMillis, lastRunAt, now);
    }

    private boolean shouldRun(long intervalMillis, long lastRunAt, long now) {
        return lastRunAt <= 0L || intervalMillis <= 0L || now - lastRunAt >= intervalMillis;
    }
}
