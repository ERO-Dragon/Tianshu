package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record SettingsEventPayload(String action, String moduleId, boolean allModules, boolean success, boolean savedAny, boolean requiresRestart, boolean requiresReload, String message) implements ITianshuPayload {
    public SettingsEventPayload {
        if (action == null) {
            action = "";
        }
        if (moduleId == null) {
            moduleId = "";
        }
        if (message == null) {
            message = "";
        }
    }
}
