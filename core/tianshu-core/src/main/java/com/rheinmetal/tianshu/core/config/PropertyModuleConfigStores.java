package com.rheinmetal.tianshu.core.config;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.config.ModuleConfigStore;
import com.rheinmetal.tianshu.api.config.ModuleConfigStores;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates one store per module id under a single directory.
 *
 * <p>Callers never see a file path: a module asks for its id and gets its settings. That keeps path
 * layout a kernel decision while the keys stay a module decision.</p>
 */
public final class PropertyModuleConfigStores implements ModuleConfigStores {

    private final Path moduleDirectory;
    private final LogSink logs;
    private final Map<String, ModuleConfigStore> stores = new ConcurrentHashMap<>();

    public PropertyModuleConfigStores(Path moduleDirectory, LogSink logs) {
        this.moduleDirectory = Objects.requireNonNull(moduleDirectory, "moduleDirectory")
                .toAbsolutePath()
                .normalize();
        this.logs = logs;
    }

    @Override
    public ModuleConfigStore forModule(String moduleId) {
        if (moduleId == null || moduleId.isBlank()) {
            throw new IllegalArgumentException("moduleId must not be blank");
        }
        return stores.computeIfAbsent(moduleId, id ->
                new PropertyModuleConfigStore(moduleDirectory.resolve(id + ".properties"), logs));
    }

    @Override
    public Path moduleDirectory() {
        return moduleDirectory;
    }
}
