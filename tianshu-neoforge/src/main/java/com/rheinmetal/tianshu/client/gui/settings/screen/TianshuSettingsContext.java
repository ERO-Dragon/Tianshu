package com.rheinmetal.tianshu.client.gui.settings.screen;

import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsCoordinator;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class TianshuSettingsContext implements ModuleSettingsContext {
    private final SettingsCoordinator settingsCoordinator;
    private Component statusMessage = Component.empty();
    private long statusMessageExpireAt;

    public TianshuSettingsContext() {
        this(new SettingsCoordinator());
    }

    public TianshuSettingsContext(SettingsCoordinator settingsCoordinator) {
        this.settingsCoordinator = settingsCoordinator == null ? new SettingsCoordinator() : settingsCoordinator;
    }

    @Override
    public Minecraft minecraft() {
        return Minecraft.getInstance();
    }

    @Override
    public SettingsCoordinator settingsCoordinator() {
        return settingsCoordinator;
    }

    @Override
    public void showStatus(Component message, long durationMillis) {
        this.statusMessage = message == null ? Component.empty() : message;
        this.statusMessageExpireAt = System.currentTimeMillis() + Math.max(0, durationMillis);
    }

    public Component statusMessage() {
        if (System.currentTimeMillis() > statusMessageExpireAt) {
            statusMessage = Component.empty();
        }
        return statusMessage;
    }
}
