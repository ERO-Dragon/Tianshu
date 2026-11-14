package com.rheinmetal.tianshu.client.api.settings;

import com.rheinmetal.tianshu.client.settings.session.SettingsCoordinator;
import com.rheinmetal.tianshu.client.settings.session.SettingsSessionRegistry;

import com.rheinmetal.tianshu.client.api.text.UiText;

public interface ModuleSettingsContext {
    SettingsCoordinator settingsCoordinator();

    default SettingsSessionRegistry settingsSessions() {
        return settingsCoordinator().sessions();
    }

    void showStatus(UiText message, long durationMillis);
}
