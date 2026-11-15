package com.rheinmetal.tianshu.core.runtime;

import com.rheinmetal.tianshu.api.LogSink;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which module provides which runtime capability, and in what state.
 *
 * <p>A capability belongs to the module that installed it: only that module may change its state afterwards. That
 * rule used to be a convention — every writer passed its own module id and nothing checked it — so a copy-pasted call
 * could reset another module's failed capability to ready and rewrite its owner along the way. It is enforced here
 * now, and a rejected write says who tried, because a silently ignored write is exactly what hid the problem.</p>
 */
public final class RuntimeCapabilityRegistry {

    private final Map<RuntimeCapability, RuntimeCapabilityStatus> statuses = new ConcurrentHashMap<>();
    private final LogSink logs;

    public RuntimeCapabilityRegistry() {
        this(LogSink.SYSTEM);
    }

    public RuntimeCapabilityRegistry(LogSink logSink) {
        this.logs = logSink == null ? LogSink.SYSTEM : logSink;
    }

    public void install(RuntimeCapability capability, String ownerModuleId) {
        write(capability, ownerModuleId, RuntimeCapabilityState.INSTALLED, null);
    }

    public void markReady(RuntimeCapability capability, String ownerModuleId) {
        write(capability, ownerModuleId, RuntimeCapabilityState.READY, null);
    }

    public void markFailed(RuntimeCapability capability, String ownerModuleId, String failureReason) {
        write(capability, ownerModuleId, RuntimeCapabilityState.FAILED, failureReason);
    }

    public void disable(RuntimeCapability capability, String ownerModuleId) {
        write(capability, ownerModuleId, RuntimeCapabilityState.DISABLED, null);
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

    /**
     * Applies one state change if {@code ownerModuleId} is allowed to make it.
     *
     * <p>The first writer owns the capability; afterwards only that module may change it. Both the ownership check and
     * the write happen inside one {@code compute}, so two modules racing to install the same capability cannot both
     * win.</p>
     */
    private void write(RuntimeCapability capability, String ownerModuleId, RuntimeCapabilityState state,
                       String failureReason) {
        if (capability == null) {
            return;
        }
        String owner = normalize(ownerModuleId);
        statuses.compute(capability, (key, existing) -> {
            if (existing != null && !ownedBy(existing, owner)) {
                logs.warn("capability " + capability.id() + " belongs to '" + existing.ownerModuleId()
                        + "'; refused a " + state + " change from '" + owner + "'");
                return existing;
            }
            return new RuntimeCapabilityStatus(capability, state, owner, failureReason);
        });
    }

    private static boolean ownedBy(RuntimeCapabilityStatus existing, String owner) {
        String current = normalize(existing.ownerModuleId());
        return current.isEmpty() || current.equals(owner);
    }

    private static String normalize(String moduleId) {
        return moduleId == null ? "" : moduleId.trim();
    }
}