package com.rheinmetal.tianshu.function.auxilium.memory;

public record AXMemoryDerivedMaintenanceResult(
        boolean ran,
        boolean stmChainRewritten,
        AXMemoryStatsSnapshot statsSnapshot
) {
    public static AXMemoryDerivedMaintenanceResult skipped() {
        return new AXMemoryDerivedMaintenanceResult(false, false, null);
    }
}
