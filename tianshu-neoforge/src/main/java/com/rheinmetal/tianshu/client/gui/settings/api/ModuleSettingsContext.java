package com.rheinmetal.tianshu.client.gui.settings.api;

import com.rheinmetal.tianshu.client.gui.settings.session.SettingsCoordinator;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsSessionRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public interface ModuleSettingsContext {
    Minecraft minecraft();

    SettingsCoordinator settingsCoordinator();

    default SettingsSessionRegistry settingsSessions() {
        return settingsCoordinator().sessions();
    }

    void showStatus(Component message, long durationMillis);
}
