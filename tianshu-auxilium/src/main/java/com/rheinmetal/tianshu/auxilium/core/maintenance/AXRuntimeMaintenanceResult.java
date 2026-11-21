package com.rheinmetal.tianshu.auxilium.core.maintenance;

public record AXRuntimeMaintenanceResult(boolean ran) {
    public static AXRuntimeMaintenanceResult skipped() {
        return new AXRuntimeMaintenanceResult(false);
    }
}
