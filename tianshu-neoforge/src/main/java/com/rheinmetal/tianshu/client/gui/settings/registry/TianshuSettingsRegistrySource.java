package com.rheinmetal.tianshu.client.gui.settings.registry;

import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;

public interface TianshuSettingsRegistrySource {
    void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context);
}
