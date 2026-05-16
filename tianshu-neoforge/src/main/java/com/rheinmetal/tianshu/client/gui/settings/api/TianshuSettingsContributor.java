package com.rheinmetal.tianshu.client.gui.settings.api;

import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistry;

public interface TianshuSettingsContributor {
    void contributeSettings(TianshuSettingsRegistry registry, ModuleSettingsContext context);
}


