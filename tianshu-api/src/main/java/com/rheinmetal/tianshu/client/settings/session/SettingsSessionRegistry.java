package com.rheinmetal.tianshu.client.settings.session;

import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.api.LogSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class SettingsSessionRegistry implements AutoCloseable {
    /** Registrations arrive from the module lifecycle thread while the page reads and saves on the client thread. */
    private final List<ModuleSettingsSession> sessions = new CopyOnWriteArrayList<>();
    private final LogSink logs;

    public SettingsSessionRegistry() {
        this(LogSink.NOOP);
    }

    public SettingsSessionRegistry(LogSink logs) {
        this.logs = Objects.requireNonNull(logs, "logs");
    }

    public void register(ModuleSettingsSession session) {
        registerOrReplace(session);
    }

    public void registerOrReplace(ModuleSettingsSession session) {
        if (session == null || session.moduleId() == null) {
            return;
        }
        sessions.removeIf(existing -> {
            if (!Objects.equals(existing.moduleId(), session.moduleId())) return false;
            if (existing != session) closeSession(existing);
            return true;
        });
        sessions.add(session);
    }

    public List<ModuleSettingsSession> sessions() {
        return List.copyOf(sessions);
    }

    public ModuleSettingsSession find(String moduleId) {
        if (moduleId == null) {
            return null;
        }
        for (ModuleSettingsSession session : sessions) {
            if (moduleId.equals(session.moduleId())) {
                return session;
            }
        }
        return null;
    }

    public boolean hasSession(String moduleId) {
        return find(moduleId) != null;
    }

    public boolean dirty() {
        for (ModuleSettingsSession session : sessions) {
            if (session.dirty()) {
                return true;
            }
        }
        return false;
    }

    public boolean dirty(String moduleId) {
        ModuleSettingsSession session = find(moduleId);
        return session != null && session.dirty();
    }

    public SettingsSaveResult saveAll() {
        List<ModuleSettingsSession> dirtySessions = new ArrayList<>();
        for (ModuleSettingsSession session : sessions) {
            if (session.dirty()) {
                dirtySessions.add(session);
            }
        }

        for (ModuleSettingsSession session : dirtySessions) {
            SettingsValidationResult validation;
            try {
                validation = session.validate();
            } catch (RuntimeException failure) {
                // A module's validate() reading config or parsing a number can throw. Letting it escape skipped every
                // later session and surfaced as an unhandled exception in the settings screen, with nothing logged.
                logs.error("settings.session.validate_failed module=" + session.moduleId(), failure);
                return SettingsSaveResult.failure(UiText.key("tianshu.gui.settings.status.failed"),
                        SettingsSaveResult.FailureType.VALIDATION);
            }
            if (!validation.success()) {
                return SettingsSaveResult.failure(validation.message(), SettingsSaveResult.FailureType.VALIDATION);
            }
        }

        boolean savedAny = false;
        boolean requiresRestart = false;
        boolean requiresReload = false;
        List<String> savedModules = new ArrayList<>();
        for (ModuleSettingsSession session : dirtySessions) {
            SettingsSaveResult result = saveSafely(session);
            if (!result.success()) {
                // A module's save is committed one at a time, so the ones before it are already on disk and cannot be
                // rolled back from here. The screen shows the failing module's message, which is right; what was
                // missing is any record of the ones that did land — a user reading "save failed" and retrying only
                // the failed module is the expected behaviour, and support reading the log needs to know the split.
                if (!savedModules.isEmpty()) {
                    logs.warn("settings.save_partial saved=" + String.join(",", savedModules)
                            + " failed=" + session.moduleId() + " (already-saved modules stay saved)");
                }
                // Partial, not plain failure: the earlier modules are on disk and their reload requirement has to
                // survive the failure message, or a retry of the failing module silently loses it.
                return SettingsSaveResult.partial(result.message(),
                        result.failureType() == SettingsSaveResult.FailureType.UNKNOWN
                                ? SettingsSaveResult.FailureType.SAVE
                                : result.failureType(),
                        savedModules, requiresRestart, requiresReload);
            }
            savedModules.add(session.moduleId());
            savedAny = true;
            requiresRestart = requiresRestart || result.requiresRestart();
            requiresReload = requiresReload || result.requiresReload();
        }
        if (!savedAny) {
            return SettingsSaveResult.unchanged(UiText.key("tianshu.gui.settings.message.no_changes"));
        }
        return SettingsSaveResult.success(UiText.key("tianshu.gui.settings.message.saved"), true, requiresRestart, requiresReload);
    }

    public SettingsSaveResult save(String moduleId) {
        ModuleSettingsSession session = find(moduleId);
        if (session == null) {
            return SettingsSaveResult.failure(UiText.key("tianshu.gui.settings.message.missing_save_session"), SettingsSaveResult.FailureType.MISSING_SESSION);
        }
        if (!session.dirty()) {
            return SettingsSaveResult.unchanged(UiText.key("tianshu.gui.settings.message.current_no_changes"));
        }
        SettingsValidationResult validation = session.validate();
        if (!validation.success()) {
            return SettingsSaveResult.failure(validation.message(), SettingsSaveResult.FailureType.VALIDATION);
        }
        SettingsSaveResult result = saveSafely(session);
        if (!result.success() && result.failureType() == SettingsSaveResult.FailureType.UNKNOWN) {
            return SettingsSaveResult.failure(result.message(), SettingsSaveResult.FailureType.SAVE);
        }
        return result.success() ? SettingsSaveResult.success(result.message(), true, result.requiresRestart(), result.requiresReload()) : result;
    }

    public SettingsSaveResult reset(String moduleId) {
        ModuleSettingsSession session = find(moduleId);
        if (session == null) {
            return SettingsSaveResult.failure(UiText.key("tianshu.gui.settings.message.missing_reset_session"), SettingsSaveResult.FailureType.MISSING_SESSION);
        }
        boolean changed = session.dirty();
        session.reset();
        return SettingsSaveResult.success(UiText.key(changed ? "tianshu.gui.settings.message.current_reset" : "tianshu.gui.settings.message.current_no_reset_changes"), changed, false, false);
    }

    private SettingsSaveResult saveSafely(ModuleSettingsSession session) {
        try {
            return session.save();
        } catch (RuntimeException failure) {
            logs.error("settings.session.save_failed module=" + session.moduleId(), failure);
            return SettingsSaveResult.failure(UiText.key("tianshu.gui.settings.status.failed"), SettingsSaveResult.FailureType.SAVE);
        }
    }

    @Override
    public void close() {
        var closing = List.copyOf(sessions);
        sessions.clear();
        closing.forEach(this::closeSession);
    }

    private void closeSession(ModuleSettingsSession session) {
        try {
            session.close();
        } catch (RuntimeException failure) {
            logs.error("settings.session.close_failed module=" + session.moduleId(), failure);
        }
    }
}
