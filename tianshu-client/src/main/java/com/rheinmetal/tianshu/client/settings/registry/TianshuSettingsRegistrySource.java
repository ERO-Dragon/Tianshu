package com.rheinmetal.tianshu.client.settings.registry;

import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;

public interface TianshuSettingsRegistrySource {
    void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context);
}
