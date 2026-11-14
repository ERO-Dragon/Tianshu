package com.rheinmetal.tianshu.client.settings.registry;

import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.api.settings.TianshuSettingsContributor;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;

import java.util.List;
import java.util.function.Supplier;

public final class ModuleSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private final Supplier<List<TianshuManagedModule>> modules;

    public ModuleSettingsRegistrySource(Supplier<List<TianshuManagedModule>> modules) {
        this.modules = modules;
    }

    @Override
    public void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        List<TianshuManagedModule> snapshot = modules == null ? List.of() : modules.get();
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        for (TianshuManagedModule module : snapshot) {
            if (module instanceof TianshuSettingsContributor contributor) {
                contributor.contributeSettings(registry, context);
            }
        }
    }
}
