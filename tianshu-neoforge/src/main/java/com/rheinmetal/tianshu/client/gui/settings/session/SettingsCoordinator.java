package com.rheinmetal.tianshu.client.gui.settings.session;

import com.rheinmetal.tianshu.client.gui.settings.protocol.SettingsEventPublisher;
import com.rheinmetal.tianshu.client.gui.settings.protocol.SettingsResetEvent;
import com.rheinmetal.tianshu.client.gui.settings.protocol.SettingsSaveEvent;
import com.rheinmetal.tianshu.client.gui.settings.protocol.SettingsValidationFailureEvent;

import net.minecraft.network.chat.Component;

public final class SettingsCoordinator {
    private final SettingsSessionRegistry sessions;
    private final SettingsEventPublisher eventPublisher;

    public SettingsCoordinator() {
        this(new SettingsSessionRegistry(), SettingsEventPublisher.NOOP);
    }

    public SettingsCoordinator(SettingsSessionRegistry sessions, SettingsEventPublisher eventPublisher) {
        this.sessions = sessions == null ? new SettingsSessionRegistry() : sessions;
        this.eventPublisher = eventPublisher == null ? SettingsEventPublisher.NOOP : eventPublisher;
    }

    public SettingsSessionRegistry sessions() {
        return sessions;
    }

    public boolean dirty() {
        return sessions.dirty();
    }

    public boolean dirty(String moduleId) {
        return sessions.dirty(moduleId);
    }

    public boolean canSave(String moduleId) {
        return sessions.dirty(moduleId);
    }

    public boolean canReset(String moduleId) {
        return sessions.dirty(moduleId);
    }

    public SettingsSaveResult saveAll() {
        SettingsSaveResult result = sessions.saveAll();
        publishFailureIfNeeded("", true, result);
        eventPublisher.publishSaved(new SettingsSaveEvent("", true, success(result), changed(result), requiresRestart(result), requiresReload(result), message(result)));
        return result;
    }

    public SettingsSaveResult save(String moduleId) {
        SettingsSaveResult result = sessions.save(moduleId);
        publishFailureIfNeeded(moduleId, false, result);
        eventPublisher.publishSaved(new SettingsSaveEvent(moduleId, false, success(result), changed(result), requiresRestart(result), requiresReload(result), message(result)));
        return result;
    }

    public SettingsSaveResult reset(String moduleId) {
        SettingsSaveResult result = sessions.reset(moduleId);
        eventPublisher.publishReset(new SettingsResetEvent(moduleId, success(result), message(result)));
        return result;
    }

    private void publishFailureIfNeeded(String moduleId, boolean allModules, SettingsSaveResult result) {
        if (result != null && !result.success() && result.failureType() == SettingsSaveResult.FailureType.VALIDATION) {
            eventPublisher.publishValidationFailed(new SettingsValidationFailureEvent(moduleId, allModules, message(result.message())));
        }
    }

    private static boolean success(SettingsSaveResult result) {
        return result != null && result.success();
    }

    private static boolean changed(SettingsSaveResult result) {
        return result != null && result.changed();
    }

    private static boolean requiresRestart(SettingsSaveResult result) {
        return result != null && result.requiresRestart();
    }

    private static boolean requiresReload(SettingsSaveResult result) {
        return result != null && result.requiresReload();
    }

    private static String message(SettingsSaveResult result) {
        return result == null ? "" : message(result.message());
    }

    private static String message(Component message) {
        return message == null ? "" : message.getString();
    }
}
