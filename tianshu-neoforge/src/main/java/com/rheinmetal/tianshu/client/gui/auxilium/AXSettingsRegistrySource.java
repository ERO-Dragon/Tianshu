package com.rheinmetal.tianshu.client.gui.auxilium;

import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.gui.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.config.ClientConfig;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.function.auxilium.AXModule;
import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;

import net.minecraft.network.chat.Component;

import java.util.Objects;

public final class AXSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private final TianshuCoreManager coreManager;
    private final ClientConfig config;

    public AXSettingsRegistrySource(TianshuCoreManager coreManager, ClientConfig config) {
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
                .options("ax.identity", ax("section.identity"), session.enabled::get, options -> options
                        .text("ax.identity.wake_word", ax("option.wake_word"), session.wakeWord, session.enabled::get))
                .toggles("ax.reply", ax("section.reply"), session.enabled::get, toggles -> toggles
                        .toggle("ax.reply.speech", ax("option.reply_speech"), session.replySpeechEnabled, session.enabled::get))
                .toggles("ax.behavior", ax("section.behavior"), session.enabled::get, toggles -> toggles
                        .toggle("ax.behavior.thinking", ax("option.chat_thinking"), session.chatThinkingEnabled, session.enabled::get)
                        .toggle("ax.behavior.interrupt", ax("option.interrupt_on_speech"), session.interruptOnPlayerSpeech, session.enabled::get));
    }

    private static Component ax(String key, Object... args) {
        return Component.translatable("tianshu.gui.ax." + key, args);
    }

    private static final class AXSettingsSession implements com.rheinmetal.tianshu.client.gui.settings.session.ModuleSettingsSession {
        private final ClientConfig config;
        private final TianshuCoreManager coreManager;
        private final MutableSettingsValue<Boolean> enabled;
        private final MutableSettingsValue<String> wakeWord;
        private final MutableSettingsValue<Boolean> replySpeechEnabled;
        private final MutableSettingsValue<Boolean> chatThinkingEnabled;
        private final MutableSettingsValue<Boolean> interruptOnPlayerSpeech;

        private AXSettingsSession(ClientConfig config, TianshuCoreManager coreManager) {
            this.config = Objects.requireNonNull(config, "config");
            this.coreManager = Objects.requireNonNull(coreManager, "coreManager");
            this.enabled = new MutableSettingsValue<>(config::assistantEnabled, config::setAxEnabled);
            this.wakeWord = new MutableSettingsValue<>(config::wakeWord, config::setAxWakeWord);
            this.replySpeechEnabled = new MutableSettingsValue<>(config::isAxReplySpeechEnabled, config::setAxReplySpeechEnabled);
            this.chatThinkingEnabled = new MutableSettingsValue<>(config::chatThinkingEnabled, config::setAxChatThinkingEnabled);
            this.interruptOnPlayerSpeech = new MutableSettingsValue<>(config::interruptOnPlayerSpeech, config::setAxInterruptOnPlayerSpeech);
        }

        @Override
        public String moduleId() {
            return AXModule.MODULE_ID;
        }

        @Override
        public boolean dirty() {
            return enabled.dirty()
                    || wakeWord.dirty()
                    || replySpeechEnabled.dirty()
                    || chatThinkingEnabled.dirty()
                    || interruptOnPlayerSpeech.dirty();
        }

        @Override
        public SettingsSaveResult save() {
            boolean changed = dirty();
            if (!changed) {
                return SettingsSaveResult.unchanged(ax("message.saved"));
            }
            boolean enabledBefore = config.assistantEnabled();
            String wakeWordBefore = config.wakeWord();
            boolean replySpeechBefore = config.isAxReplySpeechEnabled();
            boolean chatThinkingBefore = config.chatThinkingEnabled();
            boolean interruptBefore = config.interruptOnPlayerSpeech();

            config.setAxEnabled(enabled.get());
            config.setAxWakeWord(wakeWord.get());
            config.setAxReplySpeechEnabled(replySpeechEnabled.get());
            config.setAxChatThinkingEnabled(chatThinkingEnabled.get());
            config.setAxInterruptOnPlayerSpeech(interruptOnPlayerSpeech.get());
            try {
                config.save();
            } catch (RuntimeException exception) {
                config.setAxEnabled(enabledBefore);
                config.setAxWakeWord(wakeWordBefore);
                config.setAxReplySpeechEnabled(replySpeechBefore);
                config.setAxChatThinkingEnabled(chatThinkingBefore);
                config.setAxInterruptOnPlayerSpeech(interruptBefore);
                return SettingsSaveResult.failure(ax("message.save_failed"), SettingsSaveResult.FailureType.SAVE);
            }

            boolean runtimeRegistrationChanged = enabled.dirty() || wakeWord.dirty();
            enabled.save();
            wakeWord.save();
            replySpeechEnabled.save();
            chatThinkingEnabled.save();
            interruptOnPlayerSpeech.save();
            if (runtimeRegistrationChanged) {
                coreManager.refreshRuntimeAsync(RuntimeRefreshReason.RESOURCE_CHANGED, null);
            }
            return SettingsSaveResult.success(ax("message.saved"), changed, false, false);
        }

        @Override
        public void reset() {
            enabled.reset();
            wakeWord.reset();
            replySpeechEnabled.reset();
            chatThinkingEnabled.reset();
            interruptOnPlayerSpeech.reset();
        }
    }
}
