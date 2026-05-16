package com.rheinmetal.tianshu.client.gui.settings.session;

import net.minecraft.network.chat.Component;

public record SettingsSaveResult(boolean success, Component message, boolean changed, boolean requiresRestart, boolean requiresReload, FailureType failureType) {
    public SettingsSaveResult {
        message = message == null ? Component.empty() : message;
        failureType = failureType == null ? FailureType.NONE : failureType;
        if (success) {
            failureType = FailureType.NONE;
        }
    }

    public static SettingsSaveResult success(Component message) {
        return success(message, false, false, false);
    }

    public static SettingsSaveResult success(Component message, boolean requiresRestart, boolean requiresReload) {
        return success(message, true, requiresRestart, requiresReload);
    }

    public static SettingsSaveResult success(Component message, boolean changed, boolean requiresRestart, boolean requiresReload) {
        return new SettingsSaveResult(true, message, changed, requiresRestart, requiresReload, FailureType.NONE);
    }

    public static SettingsSaveResult unchanged(Component message) {
        return success(message, false, false, false);
    }

    public static SettingsSaveResult failure(Component message) {
        return failure(message, FailureType.UNKNOWN);
    }

    public static SettingsSaveResult failure(Component message, FailureType failureType) {
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
