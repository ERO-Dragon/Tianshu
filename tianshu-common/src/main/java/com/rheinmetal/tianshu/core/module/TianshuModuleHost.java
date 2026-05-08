package com.rheinmetal.tianshu.core.module;

import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TianshuModuleHost {
    private final List<TianshuManagedModule> modules = new ArrayList<>();

    public void registerModule(TianshuManagedModule module) {
        if (module != null) {
            modules.add(module);
        }
    }

    public List<TianshuManagedModule> modules() {
        return Collections.unmodifiableList(modules);
    }

    public void registerAll(ModuleRegistrationContext context) {
        for (TianshuManagedModule module : modules) {
            module.register(context);
        }
    }

    public void prepareAll(ModuleRuntimeContext context) {
        for (TianshuManagedModule module : modules) {
            module.prepare(context);
        }
    }

    public void startAll(ModuleRuntimeContext context) {
        for (TianshuManagedModule module : modules) {
            module.start(context);
        }
    }

    public void stopAll() {
        for (int i = modules.size() - 1; i >= 0; i--) {
            modules.get(i).stop();
        }
    }

    public void destroyAll() {
        for (int i = modules.size() - 1; i >= 0; i--) {
            modules.get(i).destroy();
        }
    }

    public void unregisterAll(ProtocolRuntime runtime) {
        for (int i = modules.size() - 1; i >= 0; i--) {
            runtime.unregisterModule(modules.get(i).moduleId());
        }
    }

    public void clear() {
        modules.clear();
    }
}
