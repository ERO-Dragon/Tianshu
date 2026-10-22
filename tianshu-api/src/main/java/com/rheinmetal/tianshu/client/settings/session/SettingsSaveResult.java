package com.rheinmetal.tianshu.client.settings.session;

import com.rheinmetal.tianshu.client.api.text.UiText;

public record SettingsSaveResult(boolean success, UiText message, boolean changed, boolean requiresRestart, boolean requiresReload, FailureType failureType) {
    public SettingsSaveResult {
        message = message == null ? UiText.literal("") : message;
        failureType = failureType == null ? FailureType.NONE : failureType;
        if (success) {
            failureType = FailureType.NONE;
        }
    }

    public static SettingsSaveResult success(UiText message) {
        return success(message, false, false, false);
    }

    public static SettingsSaveResult success(UiText message, boolean requiresRestart, boolean requiresReload) {
        return success(message, true, requiresRestart, requiresReload);
    }

    public static SettingsSaveResult success(UiText message, boolean changed, boolean requiresRestart, boolean requiresReload) {
        return new SettingsSaveResult(true, message, changed, requiresRestart, requiresReload, FailureType.NONE);
    }

    public static SettingsSaveResult unchanged(UiText message) {
        return success(message, false, false, false);
    }

    public static SettingsSaveResult failure(UiText message) {
        return failure(message, FailureType.UNKNOWN);
    }

    public static SettingsSaveResult failure(UiText message, FailureType failureType) {
        return new SettingsSaveResult(false, message, false, false, false, failureType);
    }

    public boolean savedAny() {
        return success && changed;
    }

    public enum FailureType {
        NONE,
        VALIDATION,
        SAVE,
        MISSING_SESSION,
        UNKNOWN
    }
}
