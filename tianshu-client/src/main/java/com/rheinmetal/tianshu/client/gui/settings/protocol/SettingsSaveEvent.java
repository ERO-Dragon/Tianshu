package com.rheinmetal.tianshu.client.gui.settings.protocol;

public record SettingsSaveEvent(String moduleId, boolean allModules, boolean success, boolean savedAny, boolean requiresRestart, boolean requiresReload, String message) {
    public SettingsSaveEvent {
        if (moduleId == null) {
            moduleId = "";
        }
        if (message == null) {
            message = "";
        }
    }
}
