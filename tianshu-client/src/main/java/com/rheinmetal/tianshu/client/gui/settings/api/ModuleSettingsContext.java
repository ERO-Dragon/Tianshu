package com.rheinmetal.tianshu.client.gui.settings.api;

import com.rheinmetal.tianshu.client.gui.settings.session.SettingsCoordinator;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsSessionRegistry;

import com.rheinmetal.tianshu.client.ui.UiText;

public interface ModuleSettingsContext {
    SettingsCoordinator settingsCoordinator();

    default SettingsSessionRegistry settingsSessions() {
        return settingsCoordinator().sessions();
    }

    void showStatus(UiText message, long durationMillis);
}
