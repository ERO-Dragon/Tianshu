package com.rheinmetal.tianshu.protocol.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ModuleRegistry {
    private final Map<String, ModuleDescriptor> modules = new ConcurrentHashMap<>();

    public void register(ModuleDescriptor descriptor) {
        ModuleDescriptor existing = modules.putIfAbsent(descriptor.moduleId(), descriptor);
        if (existing != null) {
            return;
        }
    }

    public Optional<ModuleDescriptor> find(String moduleId) {
        return Optional.ofNullable(modules.get(moduleId));
    }

    public List<ModuleDescriptor> snapshot() {
        return new ArrayList<>(modules.values());
    }
}
