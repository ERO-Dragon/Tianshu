package com.rheinmetal.tianshu.client.settings.session;

public interface ModuleSettingsSession extends AutoCloseable {
    String moduleId();

    boolean dirty();

    default SettingsValidationResult validate() {
        return SettingsValidationResult.successful();
    }

    SettingsSaveResult save();

    void reset();

    @Override
    default void close() {}
}
