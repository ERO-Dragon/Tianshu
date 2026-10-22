package com.rheinmetal.tianshu.core.runtime;

public final class ModuleRuntimeState {
    private final RuntimeReadinessState readiness = new RuntimeReadinessState();
    private final RuntimeCapabilityRegistry capabilities = new RuntimeCapabilityRegistry();

    public RuntimeReadinessState readiness() {
        return readiness;
    }

    public RuntimeCapabilityRegistry capabilities() {
        return capabilities;
    }
}
