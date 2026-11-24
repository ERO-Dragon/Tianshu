package com.rheinmetal.tianshu.client.settings.session;

import com.rheinmetal.tianshu.client.api.text.UiText;

/**
 * The outcome of saving one module, or of saving several in one action.
 *
 * <p>{@code savedModules} and the two requirement flags are meaningful on a <em>partial</em> result: when module A saved
 * and module B failed, the screen says "failed" (right) but the caller also has to know that A is already on disk and
 * whether A asked for a reload — otherwise the reload requirement is lost, because retrying only B will not produce it
 * again.</p>
 */
public record SettingsSaveResult(boolean success, UiText message, boolean changed, boolean requiresRestart,
                                 boolean requiresReload, FailureType failureType,
                                 java.util.List<String> savedModules) {

    public SettingsSaveResult {
        message = message == null ? UiText.literal("") : message;
        failureType = failureType == null ? FailureType.NONE : failureType;
        savedModules = savedModules == null ? java.util.List.of() : java.util.List.copyOf(savedModules);
        if (success) {
            failureType = FailureType.NONE;
        }
    }

    /** A save where some modules landed and one did not. */
    public static SettingsSaveResult partial(UiText message, FailureType failureType,
                                             java.util.List<String> savedModules,
                                             boolean requiresRestart, boolean requiresReload) {
        return new SettingsSaveResult(false, message, !savedModules.isEmpty(), requiresRestart, requiresReload,
                failureType == null ? FailureType.SAVE : failureType, savedModules);
    }

    public static SettingsSaveResult success(UiText message) {
        return success(message, false, false, false);
    }

    public static SettingsSaveResult success(UiText message, boolean requiresRestart, boolean requiresReload) {
        return success(message, true, requiresRestart, requiresReload);
    }

    public static SettingsSaveResult success(UiText message, boolean changed, boolean requiresRestart, boolean requiresReload) {
        return new SettingsSaveResult(true, message, changed, requiresRestart, requiresReload, FailureType.NONE,
                java.util.List.of());
    }

    public static SettingsSaveResult unchanged(UiText message) {
        return success(message, false, false, false);
    }

    public static SettingsSaveResult failure(UiText message) {
        return failure(message, FailureType.UNKNOWN);
    }

    public static SettingsSaveResult failure(UiText message, FailureType failureType) {
        return new SettingsSaveResult(false, message, false, false, false, failureType, java.util.List.of());
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
