package com.rheinmetal.tianshu.client.settings.registry;

import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.api.settings.TianshuSettingsContributor;

public final class ExternalSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private final TianshuSettingsContributorRegistry registry;

    public ExternalSettingsRegistrySource(TianshuSettingsContributorRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void contribute(TianshuSettingsRegistry settingsRegistry, ModuleSettingsContext context) {
        if (registry == null) {
            return;
        }
        for (TianshuSettingsContributor contributor : registry.contributors()) {
            contributor.contributeSettings(settingsRegistry, context);
        }
    }
}
