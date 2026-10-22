package com.rheinmetal.tianshu.core.runtime;

public record RuntimeCapabilityStatus(
        RuntimeCapability capability,
        RuntimeCapabilityState state,
        String ownerModuleId,
        String failureReason
) {
    public boolean installed() {
        return state != RuntimeCapabilityState.ABSENT;
    }

    public boolean ready() {
        return state == RuntimeCapabilityState.READY;
    }

    public boolean failed() {
        return state == RuntimeCapabilityState.FAILED;
    }
}
