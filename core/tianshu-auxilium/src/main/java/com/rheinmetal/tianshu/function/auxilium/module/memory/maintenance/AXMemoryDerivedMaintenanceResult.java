package com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance;

import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemoryStatsSnapshot;

public record AXMemoryDerivedMaintenanceResult(
        boolean ran,
        boolean stmChainRewritten,
        AXMemoryStatsSnapshot statsSnapshot
) {
    public static AXMemoryDerivedMaintenanceResult skipped() {
        return new AXMemoryDerivedMaintenanceResult(false, false, null);
    }
}
