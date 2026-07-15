package com.rheinmetal.tianshu.client.gui.settings.protocol;

public record SettingsResetEvent(String moduleId, boolean success, String message) {
    public SettingsResetEvent {
        if (moduleId == null) {
            moduleId = "";
        }
        if (message == null) {
            message = "";
        }
    }
}
