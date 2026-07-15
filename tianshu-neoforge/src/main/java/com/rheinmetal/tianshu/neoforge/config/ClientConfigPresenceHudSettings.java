package com.rheinmetal.tianshu.neoforge.config;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudSettings;
import com.rheinmetal.tianshu.function.asr.AsrProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.llm.LlmProtocolAdapter;
import com.rheinmetal.tianshu.function.tts.TtsProtocolAdapter;

public final class ClientConfigPresenceHudSettings implements PresenceHudSettings {
    private final ClientConfig config;

    public ClientConfigPresenceHudSettings(ClientConfig config) {
        this.config = config;
    }

    @Override
    public boolean hudEnabled() {
        return config == null || config.isPresenceHudEnabled();
    }

    @Override
    public boolean statusTextEnabled() {
        return config == null || config.isPresenceStatusTextEnabled();
    }

    @Override
    public boolean sourceVisible(String sourceModuleId) {
        if (config == null || sourceModuleId == null || sourceModuleId.isBlank()) {
            return true;
        }
        return switch (sourceModuleId) {
            case AsrProtocolAdapter.MODULE_ID -> config.isPresenceAsrStatusVisible();
            case LlmProtocolAdapter.MODULE_ID -> config.isPresenceLlmStatusVisible();
            case TtsProtocolAdapter.MODULE_ID -> config.isPresenceTtsStatusVisible();
            case AXProtocolAdapter.MODULE_ID -> config.isPresenceAxStatusVisible();
            default -> true;
        };
    }
}
