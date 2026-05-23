package com.rheinmetal.tianshu.client.gui.settings.session;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class ModuleSettingsSessionBuilder {
    private final String moduleId;
    private final List<SettingsValue<?>> values = new ArrayList<>();
    private Component successMessage = Component.translatable("tianshu.gui.settings.message.saved");
    private Component validationFailureMessage = Component.translatable("tianshu.gui.settings.validation.invalid_values");
    private boolean requiresRestart;
    private boolean requiresReload;

    public ModuleSettingsSessionBuilder(String moduleId) {
        this.moduleId = moduleId;
    }

    public ModuleSettingsSessionBuilder value(SettingsValue<?> value) {
        if (value != null) {
            values.add(value);
        }
        return this;
    }

    public ModuleSettingsSessionBuilder values(SettingsValue<?>... values) {
        if (values == null) {
            return this;
        }
        for (SettingsValue<?> value : values) {
            value(value);
        }
        return this;
    }

    public ModuleSettingsSessionBuilder successMessage(Component successMessage) {
        this.successMessage = successMessage == null ? Component.empty() : successMessage;
        return this;
    }

    public ModuleSettingsSessionBuilder validationFailureMessage(Component validationFailureMessage) {
        this.validationFailureMessage = validationFailureMessage == null ? Component.empty() : validationFailureMessage;
        return this;
    }

    public ModuleSettingsSessionBuilder requiresRestart(boolean requiresRestart) {
        this.requiresRestart = requiresRestart;
        return this;
    }

    public ModuleSettingsSessionBuilder requiresReload(boolean requiresReload) {
        this.requiresReload = requiresReload;
        return this;
    }

    public ModuleSettingsSession build() {
        return new ValueBackedSession(moduleId, List.copyOf(values), successMessage, validationFailureMessage, requiresRestart, requiresReload);
    }

    private record ValueBackedSession(String moduleId, List<SettingsValue<?>> values, Component successMessage, Component validationFailureMessage, boolean requiresRestart, boolean requiresReload) implements ModuleSettingsSession {
        @Override
        public boolean dirty() {
            return values.stream().anyMatch(SettingsValue::dirty);
        }

        @Override
        public SettingsValidationResult validate() {
            return values.stream().allMatch(SettingsValue::valid) ? SettingsValidationResult.successful() : SettingsValidationResult.failure(validationFailureMessage);
        }

        @Override
        public SettingsSaveResult save() {
            values.forEach(SettingsValue::save);
            return SettingsSaveResult.success(successMessage, requiresRestart, requiresReload);
        }

        @Override
        public void reset() {
            values.forEach(SettingsValue::reset);
        }
    }
}
