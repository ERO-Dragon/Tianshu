package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ModuleStatusCache {
    private final Map<ModuleStatusKey, ModuleStatus> statuses = new LinkedHashMap<>();
    private final Map<String, ModuleStatus> lifecycleStatuses = new LinkedHashMap<>();

    synchronized void accept(ModuleStatus status) {
        if (status == null) {
            return;
        }
        if (ModuleStatuses.TYPE_LIFECYCLE.equals(status.statusType())) {
            String moduleId = clean(status.moduleId());
            ModuleStatus previous = lifecycleStatuses.get(moduleId);
            if (previous == null || lifecycleGeneration(status) >= lifecycleGeneration(previous)) {
                lifecycleStatuses.put(moduleId, status);
            }
            return;
        }
        statuses.put(ModuleStatusKey.from(status), status);
    }

    public synchronized Optional<ModuleStatus> latest(String moduleId, String statusType) {
        if (ModuleStatuses.TYPE_LIFECYCLE.equals(clean(statusType))) {
            return latestLifecycle(moduleId);
        }
        ModuleStatusKey key = new ModuleStatusKey(moduleId, statusType);
        ModuleStatus status = statuses.get(key);
        if (status == null || status.expired(System.currentTimeMillis())) {
            statuses.remove(key);
            return Optional.empty();
        }
        return Optional.of(status);
    }

    public synchronized Optional<ModuleStatus> latestLifecycle(String moduleId) {
        String normalizedModuleId = clean(moduleId);
        ModuleStatus status = lifecycleStatuses.get(normalizedModuleId);
        return status == null ? Optional.empty() : Optional.of(status);
    }

    public synchronized List<ModuleStatus> byModule(String moduleId) {
        return filter(moduleId, null);
    }

    public synchronized List<ModuleStatus> byType(String statusType) {
        return filter(null, statusType);
    }

    public synchronized List<ModuleStatus> all() {
        purgeExpired();
        List<ModuleStatus> result = new ArrayList<>(statuses.values());
        result.addAll(lifecycleStatuses.values());
        return List.copyOf(result);
    }

    private List<ModuleStatus> filter(String moduleId, String statusType) {
        purgeExpired();
        List<ModuleStatus> result = new ArrayList<>();
        String moduleFilter = clean(moduleId);
        String typeFilter = clean(statusType);
        for (ModuleStatus status : statuses.values()) {
            if (!moduleFilter.isBlank() && !status.moduleId().equals(moduleFilter)) continue;
            if (!typeFilter.isBlank() && !status.statusType().equals(typeFilter)) continue;
            result.add(status);
        }
        for (ModuleStatus status : lifecycleStatuses.values()) {
            if (!moduleFilter.isBlank() && !status.moduleId().equals(moduleFilter)) continue;
            if (!typeFilter.isBlank() && !status.statusType().equals(typeFilter)) continue;
            result.add(status);
        }
        return List.copyOf(result);
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        statuses.entrySet().removeIf(entry -> entry.getValue().expired(now));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static long lifecycleGeneration(ModuleStatus status) {
        String value = status.tags().get("generation");
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private record ModuleStatusKey(String moduleId, String statusType) {
        ModuleStatusKey {
            moduleId = clean(moduleId);
            statusType = clean(statusType);
        }

        static ModuleStatusKey from(ModuleStatus status) {
            return new ModuleStatusKey(status.moduleId(), status.statusType());
        }
    }
}
