package com.rheinmetal.tianshu.client.gui.settings.registry;

import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.gui.settings.api.TianshuSettingsContributor;

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
