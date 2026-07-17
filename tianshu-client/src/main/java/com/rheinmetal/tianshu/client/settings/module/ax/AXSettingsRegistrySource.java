package com.rheinmetal.tianshu.client.settings.module.ax;

import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.function.auxilium.AXModule;
import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;

import com.rheinmetal.tianshu.client.api.text.UiText;

import java.util.Objects;

public final class AXSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private final TianshuCoreManager coreManager;
    private final AxSettingsAccess config;

    public AXSettingsRegistrySource(TianshuCoreManager coreManager, AxSettingsAccess config) {
        this.coreManager = coreManager;
        this.config = config;
    }

    @Override
    public void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        if (registry == null || context == null || coreManager == null || config == null) {
            return;
        }
        AXSettingsSession session = new AXSettingsSession(config, coreManager);
        context.settingsSessions().registerOrReplace(session);
        registry.registerCategory(ModuleSettingsCategory.builder(AXModule.MODULE_ID)
                .title(ax("title"))
                .description(ax("description"))
                .order(35)
                .panel((panel, panelContext) -> buildPanel(panel, session))
                .build());
    }

    private void buildPanel(ModuleSettingsPanel panel, AXSettingsSession session) {
        panel.enable("ax.enabled", ax("enabled"), session.enabled)
                .toggles("ax.diagnostics", common("section.diagnostics"), toggles -> toggles
                        .toggle("ax.diagnostics.enabled", common("option.diagnostics_enabled"), session.diagnosticsEnabled))
                .options("ax.identity", ax("section.identity"), session.enabled::get, options -> options
                        .text("ax.identity.wake_word", ax("option.wake_word"), session.wakeWord, session.enabled::get))
                .toggles("ax.reply", ax("section.reply"), session.enabled::get, toggles -> toggles
                        .toggle("ax.reply.speech", ax("option.reply_speech"), session.replySpeechEnabled, session.enabled::get))
                .toggles("ax.behavior", ax("section.behavior"), session.enabled::get, toggles -> toggles
                        .toggle("ax.behavior.thinking", ax("option.chat_thinking"), session.chatThinkingEnabled, session.enabled::get)
                        .toggle("ax.behavior.interrupt", ax("option.allow_interruption"), session.allowInterruption, session.enabled::get));
    }

    private static UiText ax(String key, Object... args) {
        return UiText.key("tianshu.gui.ax." + key, args);
    }

    private static UiText common(String key, Object... args) {
        return UiText.key("tianshu.gui.common." + key, args);
    }

    private static final class AXSettingsSession implements com.rheinmetal.tianshu.client.settings.session.ModuleSettingsSession {
        private final AxSettingsAccess config;
        private final TianshuCoreManager coreManager;
        private final MutableSettingsValue<Boolean> enabled;
        private final MutableSettingsValue<Boolean> diagnosticsEnabled;
        private final MutableSettingsValue<String> wakeWord;
        private final MutableSettingsValue<Boolean> replySpeechEnabled;
        private final MutableSettingsValue<Boolean> chatThinkingEnabled;
        private final MutableSettingsValue<Boolean> allowInterruption;

        private AXSettingsSession(AxSettingsAccess config, TianshuCoreManager coreManager) {
            this.config = Objects.requireNonNull(config, "config");
            this.coreManager = Objects.requireNonNull(coreManager, "coreManager");
            this.enabled = new MutableSettingsValue<>(config::assistantEnabled, config::setAxEnabled);
            this.diagnosticsEnabled = new MutableSettingsValue<>(config::isAxDiagnosticsEnabled, config::setAxDiagnosticsEnabled);
            this.wakeWord = new MutableSettingsValue<>(config::wakeWord, config::setAxWakeWord);
            this.replySpeechEnabled = new MutableSettingsValue<>(config::isAxReplySpeechEnabled, config::setAxReplySpeechEnabled);
            this.chatThinkingEnabled = new MutableSettingsValue<>(config::chatThinkingEnabled, config::setAxChatThinkingEnabled);
            this.allowInterruption = new MutableSettingsValue<>(config::allowInterruption, config::setAxAllowInterruption);
        }

        @Override
        public String moduleId() {
            return AXModule.MODULE_ID;
        }

        @Override
        public boolean dirty() {
            return enabled.dirty()
                    || diagnosticsEnabled.dirty()
                    || wakeWord.dirty()
                    || replySpeechEnabled.dirty()
                    || chatThinkingEnabled.dirty()
                    || allowInterruption.dirty();
        }

        @Override
        public SettingsSaveResult save() {
            boolean changed = dirty();
            if (!changed) {
                return SettingsSaveResult.unchanged(ax("message.saved"));
            }
            boolean enabledBefore = config.assistantEnabled();
            boolean diagnosticsBefore = config.isAxDiagnosticsEnabled();
            String wakeWordBefore = config.wakeWord();
            boolean replySpeechBefore = config.isAxReplySpeechEnabled();
            boolean chatThinkingBefore = config.chatThinkingEnabled();
            boolean interruptionBefore = config.allowInterruption();

            config.setAxEnabled(enabled.get());
            config.setAxDiagnosticsEnabled(diagnosticsEnabled.get());
            config.setAxWakeWord(wakeWord.get());
            config.setAxReplySpeechEnabled(replySpeechEnabled.get());
            config.setAxChatThinkingEnabled(chatThinkingEnabled.get());
            config.setAxAllowInterruption(allowInterruption.get());
            try {
                config.save();
            } catch (RuntimeException exception) {
                config.setAxEnabled(enabledBefore);
                config.setAxDiagnosticsEnabled(diagnosticsBefore);
                config.setAxWakeWord(wakeWordBefore);
                config.setAxReplySpeechEnabled(replySpeechBefore);
                config.setAxChatThinkingEnabled(chatThinkingBefore);
                config.setAxAllowInterruption(interruptionBefore);
                return SettingsSaveResult.failure(ax("message.save_failed"), SettingsSaveResult.FailureType.SAVE);
            }

            boolean runtimeRegistrationChanged = enabled.dirty() || wakeWord.dirty();
            enabled.save();
            diagnosticsEnabled.save();
            wakeWord.save();
            replySpeechEnabled.save();
            chatThinkingEnabled.save();
            allowInterruption.save();
            if (runtimeRegistrationChanged) {
                coreManager.refreshRuntime(RuntimeRefreshReason.RESOURCE_CHANGED);
            }
            return SettingsSaveResult.success(ax("message.saved"), changed, false, false);
        }

        @Override
        public void reset() {
            enabled.reset();
            diagnosticsEnabled.reset();
            wakeWord.reset();
            replySpeechEnabled.reset();
            chatThinkingEnabled.reset();
            allowInterruption.reset();
        }
    }
}
