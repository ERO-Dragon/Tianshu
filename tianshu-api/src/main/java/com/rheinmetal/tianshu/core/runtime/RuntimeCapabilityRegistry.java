package com.rheinmetal.tianshu.core.runtime;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RuntimeCapabilityRegistry {
    private final Map<RuntimeCapability, RuntimeCapabilityStatus> statuses = new ConcurrentHashMap<>();

    public void install(RuntimeCapability capability, String ownerModuleId) {
        if (capability == null) {
            return;
        }
        statuses.put(capability, new RuntimeCapabilityStatus(capability, RuntimeCapabilityState.INSTALLED, ownerModuleId, null));
    }

    public void markReady(RuntimeCapability capability, String ownerModuleId) {
        if (capability == null) {
            return;
        }
        statuses.put(capability, new RuntimeCapabilityStatus(capability, RuntimeCapabilityState.READY, ownerModuleId, null));
    }

    public void markFailed(RuntimeCapability capability, String ownerModuleId, String failureReason) {
        if (capability == null) {
            return;
        }
        statuses.put(capability, new RuntimeCapabilityStatus(capability, RuntimeCapabilityState.FAILED, ownerModuleId, failureReason));
    }

    public void disable(RuntimeCapability capability, String ownerModuleId) {
        if (capability == null) {
            return;
        }
        statuses.put(capability, new RuntimeCapabilityStatus(capability, RuntimeCapabilityState.DISABLED, ownerModuleId, null));
    }

    public void remove(RuntimeCapability capability) {
        if (capability != null) {
            statuses.remove(capability);
        }
    }

    public RuntimeCapabilityStatus status(RuntimeCapability capability) {
        RuntimeCapabilityStatus status = statuses.get(capability);
        if (status != null) {
            return status;
        }
        return new RuntimeCapabilityStatus(capability, RuntimeCapabilityState.ABSENT, null, null);
    }

    public boolean isReady(RuntimeCapability capability) {
        return status(capability).ready();
    }

    public Collection<RuntimeCapabilityStatus> statuses() {
        return List.copyOf(statuses.values());
    }

    public void clear() {
        statuses.clear();
    }
}
