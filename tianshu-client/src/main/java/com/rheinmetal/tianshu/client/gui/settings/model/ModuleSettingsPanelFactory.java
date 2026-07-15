package com.rheinmetal.tianshu.client.gui.settings.model;

import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsPanel;

@FunctionalInterface
public interface ModuleSettingsPanelFactory {
    void build(ModuleSettingsPanel panel, ModuleSettingsContext context);
}
