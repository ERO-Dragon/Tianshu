package com.rheinmetal.tianshu.core.runtime;

import com.rheinmetal.tianshu.core.lifecycle.status.ModuleLifecycleStatus;

import java.util.Collection;
import java.util.List;

public record CoreRuntimeStatus(
        CoreLifecyclePhase corePhase,
        RuntimeEnginePhase enginePhase,
        boolean initialized,
        Collection<RuntimeCapabilityStatus> capabilities,
        Collection<ModuleLifecycleStatus> modules
) {
    public CoreRuntimeStatus {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        modules = modules == null ? List.of() : List.copyOf(modules);
    }

    public boolean coreRunning() {
        return initialized && corePhase == CoreLifecyclePhase.RUNNING;
    }

    public boolean acceptsRuntimeRequests() {
        return coreRunning() && enginePhase != RuntimeEnginePhase.RESTARTING && enginePhase != RuntimeEnginePhase.DESTROYED;
    }

    public boolean capabilityReady(RuntimeCapability capability) {
        if (capability == null) {
            return false;
        }
        return capabilities.stream().anyMatch(status -> capability.equals(status.capability()) && status.ready());
    }
}
