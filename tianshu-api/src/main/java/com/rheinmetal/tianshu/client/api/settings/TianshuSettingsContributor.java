package com.rheinmetal.tianshu.client.api.settings;

import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistry;

public interface TianshuSettingsContributor {
    void contributeSettings(TianshuSettingsRegistry registry, ModuleSettingsContext context);
}


