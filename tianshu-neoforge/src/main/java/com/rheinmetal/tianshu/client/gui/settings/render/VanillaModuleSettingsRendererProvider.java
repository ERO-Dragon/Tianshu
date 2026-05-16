package com.rheinmetal.tianshu.client.gui.settings.render;

import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;

public final class VanillaModuleSettingsRendererProvider implements ModuleSettingsRendererProvider {
    @Override
    public ModuleSettingsRenderer createRenderer(ModuleSettingsContext context) {
        return new VanillaModuleSettingsRenderer();
    }
}
