package com.rheinmetal.tianshu.client.gui.settings.render;

import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;

public interface ModuleSettingsRendererProvider {
    ModuleSettingsRenderer createRenderer(ModuleSettingsContext context);
}
