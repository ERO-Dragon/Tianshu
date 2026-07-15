package com.rheinmetal.tianshu.client.gui.settings.protocol;

public interface SettingsEventPublisher {
    SettingsEventPublisher NOOP = new SettingsEventPublisher() {
    };

    default void publishSaved(SettingsSaveEvent event) {
    }

    default void publishReset(SettingsResetEvent event) {
    }

    default void publishValidationFailed(SettingsValidationFailureEvent event) {
    }
}
