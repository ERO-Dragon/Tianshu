package com.rheinmetal.tianshu.neoforge.ui.settings;

import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.settings.session.SettingsCoordinator;
import com.rheinmetal.tianshu.client.api.text.UiText;

public final class TianshuSettingsContext implements ModuleSettingsContext {
    private final SettingsCoordinator settingsCoordinator;
    private UiText statusMessage = UiText.literal("");
    private long statusMessageExpireAt;

    public TianshuSettingsContext() {
        this(new SettingsCoordinator());
    }

    public TianshuSettingsContext(SettingsCoordinator settingsCoordinator) {
        this.settingsCoordinator = settingsCoordinator == null ? new SettingsCoordinator() : settingsCoordinator;
    }

    @Override
    public SettingsCoordinator settingsCoordinator() {
        return settingsCoordinator;
    }

    @Override
    public void showStatus(UiText message, long durationMillis) {
        this.statusMessage = message == null ? UiText.literal("") : message;
        this.statusMessageExpireAt = System.currentTimeMillis() + Math.max(0, durationMillis);
    }

    public UiText statusMessage() {
        if (System.currentTimeMillis() > statusMessageExpireAt) {
            statusMessage = UiText.literal("");
        }
        return statusMessage;
    }
}
