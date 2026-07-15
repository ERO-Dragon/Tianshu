package com.rheinmetal.tianshu.client.settings.protocol;

public record SettingsValidationFailureEvent(String moduleId, boolean allModules, String message) {
    public SettingsValidationFailureEvent {
        if (moduleId == null) {
            moduleId = "";
        }
        if (message == null) {
            message = "";
        }
    }
}
