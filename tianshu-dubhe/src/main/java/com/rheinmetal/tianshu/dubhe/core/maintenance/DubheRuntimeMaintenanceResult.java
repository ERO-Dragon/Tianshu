package com.rheinmetal.tianshu.dubhe.core.maintenance;

public record DubheRuntimeMaintenanceResult(boolean ran) {
    public static DubheRuntimeMaintenanceResult skipped() {
        return new DubheRuntimeMaintenanceResult(false);
    }
}
