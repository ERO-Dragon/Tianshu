package com.rheinmetal.tianshu.client.settings.model;

import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsPanel;

@FunctionalInterface
public interface ModuleSettingsPanelFactory {
    void build(ModuleSettingsPanel panel, ModuleSettingsContext context);
}
