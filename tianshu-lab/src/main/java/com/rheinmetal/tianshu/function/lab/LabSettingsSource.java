package com.rheinmetal.tianshu.function.lab;

import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.api.settings.SettingsButtonStyle;
import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.settings.session.SettingsValidationResult;

/**
 * The bench's page: register the fake participants, watch what the last delivery carried, and keep or
 * release the session by hand.
 *
 * <p>It is hidden while the debug switch is off. That is not cosmetic: without the switch the module does
 * not register its participants either, so showing controls that cannot work would be a lie about the
 * session.</p>
 */
public final class LabSettingsSource implements TianshuSettingsRegistrySource {
    private final LabModuleControl module;

    public LabSettingsSource(LabModuleControl module) {
        this.module = module;
    }

    @Override
    public void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        if (registry == null || context == null || module == null) {
            return;
        }
        context.settingsSessions().registerOrReplace(new Session());
        registry.registerCategory(ModuleSettingsCategory.builder(LabModule.MODULE_ID)
                .title(lab("title"))
                .description(lab("description"))
                .order(95)
                .panel(this::buildPanel)
                .build());
    }

    private void buildPanel(ModuleSettingsPanel panel, ModuleSettingsContext context) {
        panel.toggles("lab.behaviour", lab("section.behaviour"), module::debugEnabled, module::debugEnabled, group -> group
                        .toggle("lab.auto_release", lab("option.auto_release"),
                                module::autoRelease, module::setAutoRelease, module::debugEnabled))
                .actions("lab.actions", lab("section.actions"), module::debugEnabled, module::debugEnabled, actions -> actions
                        .button("lab.register", lab("action.register"), SettingsButtonStyle.PRIMARY,
                                module::registerParticipants, () -> !module.participantsRegistered())
                        .button("lab.unregister", lab("action.unregister"), SettingsButtonStyle.DANGER,
                                module::unregisterParticipants, module::participantsRegistered)
                        .button("lab.release", lab("action.release"), module::releaseCurrentSession, module::sessionHeld)
                        .button("lab.extend", lab("action.extend"), module::extendCurrentSession, module::sessionHeld))
                .status("lab.status", lab("section.status"), module::debugEnabled, module::debugEnabled, status -> status
                        .row("lab.status.participants", lab("row.participants"), () -> onOff(module.participantsRegistered()))
                        .row("lab.status.session", lab("row.session"), () -> module.sessionHeld()
                                ? UiText.literal(module.status().sessionId()) : common("dash"))
                        .row("lab.status.owner", lab("row.owner"), () -> module.sessionHeld()
                                ? UiText.literal(module.status().ownerParticipantId()) : common("dash"))
                        .row("lab.status.delivery", lab("row.delivery"), () -> {
                            String text = module.status().lastDeliveryText();
                            return text.isBlank() ? common("dash") : UiText.literal(text);
                        })
                        .row("lab.status.error", lab("row.error"), () -> {
                            String error = module.status().lastError();
                            return error.isBlank() ? common("dash") : UiText.literal(error);
                        }));
    }

    private static UiText onOff(boolean value) {
        return common(value ? "on" : "off");
    }

    private static UiText lab(String key, Object... args) {
        return UiText.key("tianshu.gui.lab." + key, args);
    }

    private static UiText common(String key, Object... args) {
        return UiText.key("tianshu.gui.common." + key, args);
    }

    /**
     * The bench persists nothing: every control is a live test action, so the page never reports unsaved
     * changes and its save button is a no-op.
     */
    private final class Session implements com.rheinmetal.tianshu.client.settings.session.ModuleSettingsSession {
        @Override
        public String moduleId() {
            return LabModule.MODULE_ID;
        }

        @Override
        public boolean dirty() {
            return false;
        }

        @Override
        public SettingsValidationResult validate() {
            return SettingsValidationResult.successful();
        }

        @Override
        public SettingsSaveResult save() {
            return SettingsSaveResult.success(lab("message.saved"), false, false, false);
        }

        @Override
        public void reset() {
        }
    }
}