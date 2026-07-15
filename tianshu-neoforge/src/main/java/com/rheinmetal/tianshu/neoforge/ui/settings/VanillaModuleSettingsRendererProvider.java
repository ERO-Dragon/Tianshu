package com.rheinmetal.tianshu.neoforge.ui.settings;

import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;

public final class VanillaModuleSettingsRendererProvider implements ModuleSettingsRendererProvider {
    @Override
    public ModuleSettingsRenderer createRenderer(ModuleSettingsContext context) {
        return new VanillaModuleSettingsRenderer();
    }
}
