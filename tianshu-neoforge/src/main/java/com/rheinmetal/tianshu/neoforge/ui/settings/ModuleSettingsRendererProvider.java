package com.rheinmetal.tianshu.neoforge.ui.settings;

import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;

public interface ModuleSettingsRendererProvider {
    ModuleSettingsRenderer createRenderer(ModuleSettingsContext context);
}
