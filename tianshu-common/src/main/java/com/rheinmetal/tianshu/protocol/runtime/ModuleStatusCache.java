package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ModuleStatusCache {
    private final Map<ModuleStatusKey, ModuleStatus> statuses = new LinkedHashMap<>();

    synchronized void accept(ModuleStatus status) {
        if (status == null) {
            return;
        }
        statuses.put(ModuleStatusKey.from(status), status);
    }

    public synchronized Optional<ModuleStatus> latest(String moduleId, String statusType) {
        ModuleStatusKey key = new ModuleStatusKey(moduleId, statusType);
        ModuleStatus status = statuses.get(key);
        if (status == null || status.expired(System.currentTimeMillis())) {
            statuses.remove(key);
            return Optional.empty();
        }
        return Optional.of(status);
    }

    public synchronized List<ModuleStatus> byModule(String moduleId) {
        return filter(moduleId, null);
    }

    public synchronized List<ModuleStatus> byType(String statusType) {
        return filter(null, statusType);
    }

    public synchronized List<ModuleStatus> all() {
        purgeExpired();
        return List.copyOf(statuses.values());
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
        return List.copyOf(result);
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        statuses.entrySet().removeIf(entry -> entry.getValue().expired(now));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
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
