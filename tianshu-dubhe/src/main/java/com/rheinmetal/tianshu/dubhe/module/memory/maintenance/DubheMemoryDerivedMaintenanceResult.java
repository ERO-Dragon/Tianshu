package com.rheinmetal.tianshu.dubhe.module.memory.maintenance;

import com.rheinmetal.tianshu.dubhe.module.memory.DubheMemoryStatsSnapshot;

public record DubheMemoryDerivedMaintenanceResult(
        boolean ran,
        boolean stmChainRewritten,
        DubheMemoryStatsSnapshot statsSnapshot
) {
    public static DubheMemoryDerivedMaintenanceResult skipped() {
        return new DubheMemoryDerivedMaintenanceResult(false, false, null);
    }
}
