package com.rheinmetal.tianshu.core.lifecycle.module;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class ModuleServiceRegistry {
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    public <T> void register(Class<T> type, T service) {
        if (type == null || service == null) {
            return;
        }
        services.put(type, service);
    }

    public <T> void unregister(Class<T> type, T service) {
        if (type == null || service == null) {
            return;
        }
        services.remove(type, service);
    }

    public <T> Optional<T> find(Class<T> type) {
        Object service = services.get(type);
        if (service == null) {
            return Optional.empty();
        }
        return Optional.of(type.cast(service));
    }

    public <T> T require(Class<T> type) {
        return find(type).orElseThrow(() -> new IllegalStateException("Module service not registered: " + type.getName()));
    }

    public void clear() {
        services.clear();
    }
}
