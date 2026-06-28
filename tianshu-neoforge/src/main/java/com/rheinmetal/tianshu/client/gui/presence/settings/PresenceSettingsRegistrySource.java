package com.rheinmetal.tianshu.client.gui.presence.settings;

import com.rheinmetal.tianshu.client.gui.presence.debug.PresenceDebugPipelineSnapshot;
import com.rheinmetal.tianshu.client.gui.settings.api.SettingsListCard;
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
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PresenceSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private static final String MODULE_ID = PresenceProtocolAdapter.MODULE_ID;

    private final ClientConfig config;
    private final PresenceDebugPipelineSnapshot debugPipelineSnapshot;

    public PresenceSettingsRegistrySource(ClientConfig config) {
        this(config, null);
    }

    public PresenceSettingsRegistrySource(ClientConfig config, TianshuCoreManager coreManager) {
        this.config = config;
        this.debugPipelineSnapshot = new PresenceDebugPipelineSnapshot(coreManager);
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
            return SettingsListCard.text(Component.empty());
        }
        ModuleStatus status = row.status();
        Component title = Component.translatable(row.labelKey());
        if (status == null) {
            return new SettingsListCard(
                    title,
                    presence("debug.status.no_status"),
                    List.of(Component.literal(row.moduleId())),
                    List.of(presence("debug.badge.no_status"))
            );
        }

        List<Component> details = new ArrayList<>();
        details.add(presence("debug.detail.status_type", Component.literal(status.statusType())));
        Component message = statusMessage(status);
        if (!message.getString().isBlank()) {
            details.add(presence("debug.detail.message", message));
        }
        String pipelineStage = status.tags().getOrDefault("axPipelineStage", "");
        if (!pipelineStage.isBlank()) {
            details.add(presence("debug.detail.stage", Component.literal(pipelineStage)));
        }
        details.add(presence("debug.detail.age", Component.literal(Long.toString(ageSeconds(status)))));

        return new SettingsListCard(
                title,
                severity(status),
                details,
                List.of(Component.literal(status.severity().name()))
        );
    }

    private Component statusMessage(ModuleStatus status) {
        if (status == null) {
            return Component.empty();
        }
        if (!status.messageKey().isBlank() && I18n.exists(status.messageKey())) {
            return Component.translatable(status.messageKey());
        }
        if (!status.fallbackMessage().isBlank()) {
            return Component.literal(status.fallbackMessage());
        }
        return Component.empty();
    }

    private Component severity(ModuleStatus status) {
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
        private final MutableSettingsValue<Boolean> debugPipelineEnabled;

        private PresenceSettingsSession(ClientConfig config) {
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
