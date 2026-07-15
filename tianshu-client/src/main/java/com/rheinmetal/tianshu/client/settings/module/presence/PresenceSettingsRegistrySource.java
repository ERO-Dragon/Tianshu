package com.rheinmetal.tianshu.client.settings.module.presence;

import com.rheinmetal.tianshu.client.presence.diagnostics.PresenceDebugPipelineSnapshot;
import com.rheinmetal.tianshu.client.api.settings.SettingsListCard;
import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.settings.session.SettingsValidationResult;
import com.rheinmetal.tianshu.client.presence.PresenceProtocolAdapter;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.presence.PresenceTextProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PresenceSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private static final String MODULE_ID = PresenceProtocolAdapter.MODULE_ID;

    private final PresenceSettingsAccess config;
    private final PresenceTextProvider textProvider;
    private final PresenceDebugPipelineSnapshot debugPipelineSnapshot;

    public PresenceSettingsRegistrySource(PresenceSettingsAccess config, PresenceTextProvider textProvider) {
        this(config, null, textProvider);
    }

    public PresenceSettingsRegistrySource(PresenceSettingsAccess config, TianshuCoreManager coreManager, PresenceTextProvider textProvider) {
        this.config = config;
        this.debugPipelineSnapshot = new PresenceDebugPipelineSnapshot(coreManager);
        this.textProvider = textProvider == null ? PresenceTextProvider.NOOP : textProvider;
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
                .toggles("presence.debug.options", presence("section.debug"), group -> group
                        .toggle("presence.debug.pipeline", presence("option.debug_pipeline"), session.debugPipelineEnabled))
                .status("presence.hud.status", presence("section.current"), status -> status
                        .row("presence.status.hud", presence("row.hud"), () -> common(session.hudEnabled.get() ? "on" : "off"))
                        .row("presence.status.text", presence("row.status_text"), () -> common(session.statusTextEnabled.get() ? "on" : "off")))
                .<PresenceDebugPipelineSnapshot.Row>list("presence.debug.pipeline", presence("section.debug_pipeline"), () -> session.debugPipelineEnabled.get(), list -> list
                        .items(debugPipelineSnapshot::rows)
                        .card(this::debugPipelineCard)
                        .emptyText(presence("debug.empty")));
    }

    private SettingsListCard debugPipelineCard(PresenceDebugPipelineSnapshot.Row row) {
        if (row == null) {
            return SettingsListCard.text(UiText.literal(""));
        }
        ModuleStatus status = row.status();
        UiText title = UiText.key(row.labelKey());
        if (status == null) {
            return new SettingsListCard(
                    title,
                    presence("debug.status.no_status"),
                    List.of(UiText.literal(row.moduleId())),
                    List.of(presence("debug.badge.no_status"))
            );
        }

        List<UiText> details = new ArrayList<>();
        details.add(presence("debug.detail.status_type", UiText.literal(status.statusType())));
        UiText message = statusMessage(status);
        if (!message.isBlank()) {
            details.add(presence("debug.detail.message", message));
        }
        String pipelineStage = status.tags().getOrDefault("axPipelineStage", "");
        if (!pipelineStage.isBlank()) {
            details.add(presence("debug.detail.stage", UiText.literal(pipelineStage)));
        }
        details.add(presence("debug.detail.age", UiText.literal(Long.toString(ageSeconds(status)))));

        return new SettingsListCard(
                title,
                severity(status),
                details,
                List.of(UiText.literal(status.severity().name()))
        );
    }

    private UiText statusMessage(ModuleStatus status) {
        if (status == null) {
            return UiText.literal("");
        }
        if (!status.messageKey().isBlank() && textProvider.exists(status.messageKey())) {
            return UiText.key(status.messageKey());
        }
        if (!status.fallbackMessage().isBlank()) {
            return UiText.literal(status.fallbackMessage());
        }
        return UiText.literal("");
    }

    private UiText severity(ModuleStatus status) {
        if (status == null || status.severity() == null) {
            return common("unknown");
        }
        return presence("debug.severity." + status.severity().name().toLowerCase(Locale.ROOT));
    }

    private long ageSeconds(ModuleStatus status) {
        if (status == null) {
            return 0L;
        }
        return Math.max(0L, (System.currentTimeMillis() - status.updatedAtMillis()) / 1000L);
    }

    private static UiText presence(String key, Object... args) {
        return UiText.key("tianshu.gui.presence." + key, args);
    }

    private static UiText common(String key, Object... args) {
        return UiText.key("tianshu.gui.common." + key, args);
    }

    private static final class PresenceSettingsSession implements com.rheinmetal.tianshu.client.settings.session.ModuleSettingsSession {
        private final PresenceSettingsAccess config;
        private final MutableSettingsValue<Boolean> hudEnabled;
        private final MutableSettingsValue<Boolean> statusTextEnabled;
        private final MutableSettingsValue<Boolean> asrStatusVisible;
        private final MutableSettingsValue<Boolean> llmStatusVisible;
        private final MutableSettingsValue<Boolean> ttsStatusVisible;
        private final MutableSettingsValue<Boolean> axStatusVisible;
        private final MutableSettingsValue<Boolean> debugPipelineEnabled;

        private PresenceSettingsSession(PresenceSettingsAccess config) {
            this.config = config;
            this.hudEnabled = new MutableSettingsValue<>(config::isPresenceHudEnabled, config::setPresenceHudEnabled);
            this.statusTextEnabled = new MutableSettingsValue<>(config::isPresenceStatusTextEnabled, config::setPresenceStatusTextEnabled);
            this.asrStatusVisible = new MutableSettingsValue<>(config::isPresenceAsrStatusVisible, config::setPresenceAsrStatusVisible);
            this.llmStatusVisible = new MutableSettingsValue<>(config::isPresenceLlmStatusVisible, config::setPresenceLlmStatusVisible);
            this.ttsStatusVisible = new MutableSettingsValue<>(config::isPresenceTtsStatusVisible, config::setPresenceTtsStatusVisible);
            this.axStatusVisible = new MutableSettingsValue<>(config::isPresenceAxStatusVisible, config::setPresenceAxStatusVisible);
            this.debugPipelineEnabled = new MutableSettingsValue<>(config::isPresenceDebugPipelineEnabled, config::setPresenceDebugPipelineEnabled);
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
                    || axStatusVisible.dirty()
                    || debugPipelineEnabled.dirty();
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
            debugPipelineEnabled.save();
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
            debugPipelineEnabled.reset();
        }
    }
}
