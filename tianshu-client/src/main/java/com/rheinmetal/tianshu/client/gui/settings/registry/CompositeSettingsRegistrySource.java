package com.rheinmetal.tianshu.client.gui.settings.registry;

import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;

import java.util.List;

public final class CompositeSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private final List<TianshuSettingsRegistrySource> sources;

    public CompositeSettingsRegistrySource(List<TianshuSettingsRegistrySource> sources) {
        this.sources = List.copyOf(sources);
    }

    public static CompositeSettingsRegistrySource of(TianshuSettingsRegistrySource... sources) {
        return new CompositeSettingsRegistrySource(List.of(sources));
    }

    @Override
    public void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        for (TianshuSettingsRegistrySource source : sources) {
            source.contribute(registry, context);
        }
    }
}
