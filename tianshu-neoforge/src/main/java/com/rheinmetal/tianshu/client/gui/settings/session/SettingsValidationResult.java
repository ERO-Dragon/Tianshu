package com.rheinmetal.tianshu.client.gui.settings.session;

import net.minecraft.network.chat.Component;

public record SettingsValidationResult(boolean success, Component message) {
    public static SettingsValidationResult successful() {
        return new SettingsValidationResult(true, Component.empty());
    }

    public static SettingsValidationResult failure(Component message) {
        return new SettingsValidationResult(false, message == null ? Component.empty() : message);
    }
}
