package com.rheinmetal.tianshu.client.gui.auxilium;

import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.gui.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsValidationResult;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.function.auxilium.AXModule;
import com.rheinmetal.tianshu.function.auxilium.output.AXOutputMode;
import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Objects;

public final class AXSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private final TianshuCoreManager coreManager;
    private final AXClientConfig config;

    public AXSettingsRegistrySource(TianshuCoreManager coreManager, AXClientConfig config) {
        this.coreManager = coreManager;
        this.config = config;
    }

    @Override
    public void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        if (registry == null || context == null || coreManager == null || config == null) {
            return;
        }
        registry.registerCategory(ModuleSettingsCategory.builder(AXModule.MODULE_ID)
                .title(ax("title"))
                .description(ax("description"))
                .order(35)
                .panel(this::buildPanel)
                .build());
    }

    private void buildPanel(ModuleSettingsPanel panel, ModuleSettingsContext context) {
        AXSettingsSession session = new AXSettingsSession(config, coreManager);
        context.settingsSessions().registerOrReplace(session);
        panel.options("ax.output", ax("section.output"), options -> options
                        .text("ax.identity.wake_word", ax("option.wake_word"), session.wakeWord)
                        .select("ax.output.mode", ax("option.output_mode"), List.of(AXOutputMode.values()), session.outputMode, this::modeLabel)
                        .text("ax.output.voice_style", ax("option.voice_style"), session.voiceStyle, () -> session.outputMode.get().ttsEnabled()))
                .status("ax.output.status", ax("section.status"), status -> status
                        .row("ax.output.wake_word", ax("row.wake_word"), () -> Component.literal(session.wakeWord.get()))
                        .row("ax.output.current_mode", ax("row.output_mode"), () -> modeLabel(session.outputMode.get()))
                        .row("ax.output.config_path", ax("row.config_path"), () -> Component.literal(config.path() == null ? "-" : config.path().toString())));
    }

    private Component modeLabel(AXOutputMode mode) {
        AXOutputMode effective = mode == null ? AXOutputMode.UI_ONLY : mode;
        return ax("mode." + effective.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static Component ax(String key, Object... args) {
        return Component.translatable("tianshu.gui.ax." + key, args);
    }

    private static final class AXSettingsSession implements com.rheinmetal.tianshu.client.gui.settings.session.ModuleSettingsSession {
        private final AXClientConfig config;
        private final TianshuCoreManager coreManager;
        private final MutableSettingsValue<String> wakeWord;
        private final MutableSettingsValue<AXOutputMode> outputMode;
        private final MutableSettingsValue<String> voiceStyle;

        private AXSettingsSession(AXClientConfig config, TianshuCoreManager coreManager) {
            this.config = Objects.requireNonNull(config, "config");
            this.coreManager = Objects.requireNonNull(coreManager, "coreManager");
            this.wakeWord = new MutableSettingsValue<>(config::wakeWord, config::setWakeWord, value -> value != null && !value.isBlank());
            this.outputMode = new MutableSettingsValue<>(config::outputMode, config::setOutputMode, Objects::nonNull);
            this.voiceStyle = new MutableSettingsValue<>(config::ttsVoiceStyle, config::setTtsVoiceStyle, value -> value != null && !value.isBlank());
        }

        @Override
        public String moduleId() {
            return AXModule.MODULE_ID;
        }

        @Override
        public boolean dirty() {
            return wakeWord.dirty() || outputMode.dirty() || voiceStyle.dirty();
        }

        @Override
        public SettingsValidationResult validate() {
            if (!wakeWord.valid()) {
                return SettingsValidationResult.failure(ax("validation.wake_word_empty"));
            }
            return SettingsValidationResult.successful();
        }

        @Override
        public SettingsSaveResult save() {
            boolean changed = dirty();
            boolean wakeWordChanged = wakeWord.dirty();
            wakeWord.save();
            outputMode.save();
            voiceStyle.save();
            config.save();
            if (wakeWordChanged) {
                coreManager.refreshRuntimeAsync(RuntimeRefreshReason.RESOURCE_CHANGED, null);
            }
            return SettingsSaveResult.success(ax("message.saved"), changed, false, false);
        }

        @Override
        public void reset() {
            wakeWord.reset();
            outputMode.reset();
            voiceStyle.reset();
        }
    }
}
