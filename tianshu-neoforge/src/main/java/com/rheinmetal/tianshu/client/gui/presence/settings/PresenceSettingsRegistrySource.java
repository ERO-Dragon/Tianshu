package com.rheinmetal.tianshu.client.gui.presence.settings;

import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.gui.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsValidationResult;
import com.rheinmetal.tianshu.client.presence.PresenceProtocolAdapter;
import com.rheinmetal.tianshu.config.ClientConfig;
import net.minecraft.network.chat.Component;

public final class PresenceSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private static final String MODULE_ID = PresenceProtocolAdapter.MODULE_ID;

    private final ClientConfig config;

    public PresenceSettingsRegistrySource(ClientConfig config) {
        this.config = config;
    }

    @Override
    public void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        if (registry == null || context == null || config == null) {
            return;
        }
        PresenceSettingsSession session = new PresenceSettingsSession(config);
        context.settingsSessions().registerOrReplace(session);
        registry.registerCategory(ModuleSettingsCategory.builder(MODULE_ID)
                .title(presence("title"))
                .description(presence("description"))
                .order(45)
                .panel((panel, panelContext) -> buildPanel(panel, session))
                .build());
    }

    private void buildPanel(ModuleSettingsPanel panel, PresenceSettingsSession session) {
        panel.toggles("presence.hud.elements", presence("section.hud_elements"), group -> group
                        .toggle("presence.hud.enabled", presence("option.hud_enabled"), session.hudEnabled)
                        .toggle("presence.hud.status_text", presence("option.status_text"), session.statusTextEnabled, session.hudEnabled::get))
                .toggles("presence.hud.sources", presence("section.module_sources"), session.hudEnabled::get, group -> group
                        .toggle("presence.source.asr", presence("module.asr"), session.asrStatusVisible)
                        .toggle("presence.source.llm", presence("module.llm"), session.llmStatusVisible)
                        .toggle("presence.source.tts", presence("module.tts"), session.ttsStatusVisible)
                        .toggle("presence.source.ax", presence("module.ax"), session.axStatusVisible))
                .status("presence.hud.status", presence("section.current"), status -> status
                        .row("presence.status.hud", presence("row.hud"), () -> common(session.hudEnabled.get() ? "on" : "off"))
                        .row("presence.status.text", presence("row.status_text"), () -> common(session.statusTextEnabled.get() ? "on" : "off")));
    }

    private static Component presence(String key, Object... args) {
        return Component.translatable("tianshu.gui.presence." + key, args);
    }

    private static Component common(String key, Object... args) {
        return Component.translatable("tianshu.gui.common." + key, args);
    }

    private static final class PresenceSettingsSession implements com.rheinmetal.tianshu.client.gui.settings.session.ModuleSettingsSession {
        private final ClientConfig config;
        private final MutableSettingsValue<Boolean> hudEnabled;
        private final MutableSettingsValue<Boolean> statusTextEnabled;
        private final MutableSettingsValue<Boolean> asrStatusVisible;
        private final MutableSettingsValue<Boolean> llmStatusVisible;
        private final MutableSettingsValue<Boolean> ttsStatusVisible;
        private final MutableSettingsValue<Boolean> axStatusVisible;

        private PresenceSettingsSession(ClientConfig config) {
            this.config = config;
            this.hudEnabled = new MutableSettingsValue<>(config::isPresenceHudEnabled, config::setPresenceHudEnabled);
            this.statusTextEnabled = new MutableSettingsValue<>(config::isPresenceStatusTextEnabled, config::setPresenceStatusTextEnabled);
            this.asrStatusVisible = new MutableSettingsValue<>(config::isPresenceAsrStatusVisible, config::setPresenceAsrStatusVisible);
            this.llmStatusVisible = new MutableSettingsValue<>(config::isPresenceLlmStatusVisible, config::setPresenceLlmStatusVisible);
            this.ttsStatusVisible = new MutableSettingsValue<>(config::isPresenceTtsStatusVisible, config::setPresenceTtsStatusVisible);
            this.axStatusVisible = new MutableSettingsValue<>(config::isPresenceAxStatusVisible, config::setPresenceAxStatusVisible);
        }

        @Override
        public String moduleId() {
            return MODULE_ID;
        }

        @Override
        public boolean dirty() {
            return hudEnabled.dirty()
                    || statusTextEnabled.dirty()
                    || asrStatusVisible.dirty()
                    || llmStatusVisible.dirty()
                    || ttsStatusVisible.dirty()
                    || axStatusVisible.dirty();
        }

        @Override
        public SettingsValidationResult validate() {
            return SettingsValidationResult.successful();
        }

        @Override
        public SettingsSaveResult save() {
            boolean changed = dirty();
            hudEnabled.save();
            statusTextEnabled.save();
            asrStatusVisible.save();
            llmStatusVisible.save();
            ttsStatusVisible.save();
            axStatusVisible.save();
            config.save();
            return SettingsSaveResult.success(presence("message.saved"), changed, false, false);
        }

        @Override
        public void reset() {
            hudEnabled.reset();
            statusTextEnabled.reset();
            asrStatusVisible.reset();
            llmStatusVisible.reset();
            ttsStatusVisible.reset();
            axStatusVisible.reset();
        }
    }
}
