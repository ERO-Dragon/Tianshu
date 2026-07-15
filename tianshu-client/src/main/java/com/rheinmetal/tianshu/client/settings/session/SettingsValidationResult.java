package com.rheinmetal.tianshu.client.settings.session;

import com.rheinmetal.tianshu.client.api.text.UiText;

public record SettingsValidationResult(boolean success, UiText message) {
    public static SettingsValidationResult successful() {
        return new SettingsValidationResult(true, UiText.literal(""));
    }

    public static SettingsValidationResult failure(UiText message) {
        return new SettingsValidationResult(false, message == null ? UiText.literal("") : message);
    }
}
