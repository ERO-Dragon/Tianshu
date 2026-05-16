package com.rheinmetal.tianshu.client.integration;

import com.rheinmetal.tianshu.client.gui.settings.api.TianshuSettingsContributor;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsContributorRegistry;
import com.rheinmetal.tianshu.integration.TianshuIntegrationApi;
import net.neoforged.bus.api.Event;

public final class TianshuIntegrationRegisterEvent extends Event {
    private final TianshuIntegrationApi api;
    private final TianshuSettingsContributorRegistry settingsContributors;

    public TianshuIntegrationRegisterEvent(TianshuIntegrationApi api, TianshuSettingsContributorRegistry settingsContributors) {
        if (api == null) {
            throw new IllegalArgumentException("api cannot be null");
        }
        this.api = api;
        this.settingsContributors = settingsContributors;
    }

    public TianshuIntegrationApi api() {
        return api;
    }

    public void registerSettingsContributor(TianshuSettingsContributor contributor) {
        if (settingsContributors != null) {
            settingsContributors.register(contributor);
        }
    }

    public void unregisterSettingsContributor(TianshuSettingsContributor contributor) {
        if (settingsContributors != null) {
            settingsContributors.unregister(contributor);
        }
    }
}
