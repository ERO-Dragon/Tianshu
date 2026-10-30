package com.rheinmetal.tianshu.mc.ui.settings;

import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;

public interface ModuleSettingsRendererProvider {
    ModuleSettingsRenderer createRenderer(ModuleSettingsContext context);
}
