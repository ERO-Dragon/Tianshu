package com.rheinmetal.tianshu.function.auxilium.runtime;

public record AXRuntimeMaintenanceResult(boolean ran) {
    public static AXRuntimeMaintenanceResult skipped() {
        return new AXRuntimeMaintenanceResult(false);
    }
}
