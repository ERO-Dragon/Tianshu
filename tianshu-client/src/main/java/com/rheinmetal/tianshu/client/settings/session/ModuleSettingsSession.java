package com.rheinmetal.tianshu.client.settings.session;

public interface ModuleSettingsSession {
    String moduleId();

    boolean dirty();

    default SettingsValidationResult validate() {
        return SettingsValidationResult.successful();
    }

    SettingsSaveResult save();

    void reset();
}
