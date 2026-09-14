package com.rheinmetal.tianshu.client.settings.session;

import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.api.LogSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SettingsSessionRegistry implements AutoCloseable {
    private final List<ModuleSettingsSession> sessions = new ArrayList<>();
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
            SettingsValidationResult validation = session.validate();
            if (!validation.success()) {
                return SettingsSaveResult.failure(validation.message(), SettingsSaveResult.FailureType.VALIDATION);
            }
        }

        boolean savedAny = false;
        boolean requiresRestart = false;
        boolean requiresReload = false;
        for (ModuleSettingsSession session : dirtySessions) {
            SettingsSaveResult result = saveSafely(session);
            if (!result.success()) {
                return result.failureType() == SettingsSaveResult.FailureType.UNKNOWN ? SettingsSaveResult.failure(result.message(), SettingsSaveResult.FailureType.SAVE) : result;
            }
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
