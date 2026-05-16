package com.rheinmetal.tianshu.client.gui.settings.render;

import com.rheinmetal.tianshu.client.gui.settings.layout.SettingsViewport;
import com.rheinmetal.tianshu.client.gui.settings.model.SettingsTemplateModel;
import com.rheinmetal.tianshu.client.gui.settings.screen.TianshuSettingsScreen;

import net.minecraft.client.gui.Font;

import java.util.List;

public interface ModuleSettingsRenderer {
    SettingsRenderResult render(TianshuSettingsScreen screen, Font font, int x, int y, int width, SettingsViewport viewport, List<SettingsTemplateModel> templates);
}
