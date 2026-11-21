package com.rheinmetal.tianshu.auxilium.module.memory.maintenance;

import com.rheinmetal.tianshu.auxilium.module.memory.AXMemoryStatsSnapshot;

public record AXMemoryDerivedMaintenanceResult(
        boolean ran,
        boolean stmChainRewritten,
        AXMemoryStatsSnapshot statsSnapshot
) {
    public static AXMemoryDerivedMaintenanceResult skipped() {
        return new AXMemoryDerivedMaintenanceResult(false, false, null);
    }
}
