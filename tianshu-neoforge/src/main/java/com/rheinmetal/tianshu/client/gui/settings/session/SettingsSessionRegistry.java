package com.rheinmetal.tianshu.client.gui.settings.session;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SettingsSessionRegistry {
    private final List<ModuleSettingsSession> sessions = new ArrayList<>();

    public void register(ModuleSettingsSession session) {
        if (session != null) {
            sessions.add(session);
        }
    }

    public void registerOrReplace(ModuleSettingsSession session) {
        if (session == null || session.moduleId() == null) {
            return;
        }
        sessions.removeIf(existing -> Objects.equals(existing.moduleId(), session.moduleId()));
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
        boolean savedAny = false;
        boolean requiresRestart = false;
        boolean requiresReload = false;
        for (ModuleSettingsSession session : sessions) {
            if (!session.dirty()) {
                continue;
            }
            SettingsValidationResult validation = session.validate();
            if (!validation.success()) {
                return SettingsSaveResult.failure(validation.message(), SettingsSaveResult.FailureType.VALIDATION);
            }
            SettingsSaveResult result = session.save();
            if (!result.success()) {
                return result.failureType() == SettingsSaveResult.FailureType.UNKNOWN ? SettingsSaveResult.failure(result.message(), SettingsSaveResult.FailureType.SAVE) : result;
            }
            savedAny = true;
            requiresRestart = requiresRestart || result.requiresRestart();
            requiresReload = requiresReload || result.requiresReload();
        }
        if (!savedAny) {
            return SettingsSaveResult.unchanged(Component.literal("没有需要保存的修改"));
        }
        return SettingsSaveResult.success(Component.literal("设置已保存"), true, requiresRestart, requiresReload);
    }

    public SettingsSaveResult save(String moduleId) {
        ModuleSettingsSession session = find(moduleId);
        if (session == null) {
            return SettingsSaveResult.failure(Component.literal("当前模块没有可保存的设置会话"), SettingsSaveResult.FailureType.MISSING_SESSION);
        }
        if (!session.dirty()) {
            return SettingsSaveResult.unchanged(Component.literal("当前模块没有需要保存的修改"));
        }
        SettingsValidationResult validation = session.validate();
        if (!validation.success()) {
            return SettingsSaveResult.failure(validation.message(), SettingsSaveResult.FailureType.VALIDATION);
        }
        SettingsSaveResult result = session.save();
        if (!result.success() && result.failureType() == SettingsSaveResult.FailureType.UNKNOWN) {
            return SettingsSaveResult.failure(result.message(), SettingsSaveResult.FailureType.SAVE);
        }
        return result.success() ? SettingsSaveResult.success(result.message(), true, result.requiresRestart(), result.requiresReload()) : result;
    }

    public SettingsSaveResult reset(String moduleId) {
        ModuleSettingsSession session = find(moduleId);
        if (session == null) {
            return SettingsSaveResult.failure(Component.literal("当前模块没有可重置的设置会话"), SettingsSaveResult.FailureType.MISSING_SESSION);
        }
        boolean changed = session.dirty();
        session.reset();
        return SettingsSaveResult.success(Component.literal(changed ? "当前模块设置已重置" : "当前模块没有需要重置的修改"), changed, false, false);
    }
}
