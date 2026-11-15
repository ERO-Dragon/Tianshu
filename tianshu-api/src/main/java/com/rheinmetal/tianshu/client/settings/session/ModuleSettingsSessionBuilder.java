package com.rheinmetal.tianshu.client.settings.session;

import com.rheinmetal.tianshu.client.api.text.UiText;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds a module's settings session out of draft values plus the step that puts them on disk.
 *
 * <p>The persist step is mandatory, and {@link #build()} refuses to produce a session without one. It used to be
 * optional and missing: the session wrote each value into whatever the module bound it to and answered "saved", while
 * nothing ever called the module's config store. A module that adopted this builder therefore reported success for
 * values that only existed in memory — the one outcome a settings screen must never produce.</p>
 *
 * <p>Saving goes through {@link SettingsSaveTransaction}, so a failure while persisting rolls the staged writes back
 * instead of leaving the live configuration half-updated.</p>
 */
public final class ModuleSettingsSessionBuilder {
    private final String moduleId;
    private final List<MutableSettingsValue<?>> values = new ArrayList<>();
    private Runnable persist;
    private UiText successMessage = UiText.key("tianshu.gui.settings.message.saved");
    private UiText validationFailureMessage = UiText.key("tianshu.gui.settings.validation.invalid_values");
    private boolean requiresRestart;
    private boolean requiresReload;

    public ModuleSettingsSessionBuilder(String moduleId) {
        this.moduleId = moduleId;
    }

    public ModuleSettingsSessionBuilder value(MutableSettingsValue<?> value) {
        if (value != null) {
            values.add(value);
        }
        return this;
    }

    public ModuleSettingsSessionBuilder values(MutableSettingsValue<?>... values) {
        if (values == null) {
            return this;
        }
        for (MutableSettingsValue<?> value : values) {
            value(value);
        }
        return this;
    }

    /** How the values reach disk: usually the module config store's own save. */
    public ModuleSettingsSessionBuilder persist(Runnable persist) {
        this.persist = persist;
        return this;
    }

    public ModuleSettingsSessionBuilder successMessage(UiText successMessage) {
        this.successMessage = successMessage == null ? UiText.literal("") : successMessage;
        return this;
    }

    public ModuleSettingsSessionBuilder validationFailureMessage(UiText validationFailureMessage) {
        this.validationFailureMessage = validationFailureMessage == null ? UiText.literal("") : validationFailureMessage;
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
        if (persist == null) {
            throw new IllegalStateException("module " + moduleId
                    + ": a settings session must declare how its values reach disk (persist(...))");
        }
        return new ValueBackedSession(moduleId, List.copyOf(values), persist, successMessage,
                validationFailureMessage, requiresRestart, requiresReload);
    }

    private record ValueBackedSession(String moduleId, List<MutableSettingsValue<?>> values, Runnable persist,
                                      UiText successMessage, UiText validationFailureMessage,
                                      boolean requiresRestart, boolean requiresReload)
            implements ModuleSettingsSession {
        @Override
        public boolean dirty() {
            return values.stream().anyMatch(MutableSettingsValue::dirty);
        }

        @Override
        public SettingsValidationResult validate() {
            return values.stream().allMatch(MutableSettingsValue::valid)
                    ? SettingsValidationResult.successful()
                    : SettingsValidationResult.failure(validationFailureMessage);
        }

        @Override
        public SettingsSaveResult save() {
            SettingsSaveTransaction transaction =
                    new SettingsSaveTransaction(values.toArray(new MutableSettingsValue<?>[0]));
            transaction.commit(Objects.requireNonNull(persist, "persist"));
            return SettingsSaveResult.success(successMessage, requiresRestart, requiresReload);
        }

        @Override
        public void reset() {
            values.forEach(MutableSettingsValue::reset);
        }
    }
}