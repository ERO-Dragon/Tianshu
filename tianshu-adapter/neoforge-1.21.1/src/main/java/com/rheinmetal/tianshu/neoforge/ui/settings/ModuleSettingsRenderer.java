package com.rheinmetal.tianshu.neoforge.ui.settings;

import com.rheinmetal.tianshu.client.settings.layout.SettingsViewport;
import com.rheinmetal.tianshu.client.settings.model.SettingsTemplateModel;
import com.rheinmetal.tianshu.neoforge.ui.settings.TianshuSettingsScreen;

import net.minecraft.client.gui.Font;

import java.util.List;

public interface ModuleSettingsRenderer {
    SettingsRenderResult render(TianshuSettingsScreen screen, Font font, int x, int y, int width, SettingsViewport viewport, List<SettingsTemplateModel> templates);
}
