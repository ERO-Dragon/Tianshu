package com.rheinmetal.tianshu.neoforge.config;

import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.function.auxilium.AXAssistantSettings;
import com.rheinmetal.tianshu.function.asr.settings.AsrConfiguration;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputMode;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageConfiguration;
import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.tts.settings.TtsConfiguration;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.AsrModelManager;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceConfiguration;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ClientConfig implements AsrConfiguration, LlmConfiguration, TtsConfiguration,
        AXStorageConfiguration, VoiceResourceConfiguration, AXAssistantSettings, AXOutputSettings,
        com.rheinmetal.tianshu.client.settings.module.ax.AxSettingsAccess,
        com.rheinmetal.tianshu.client.settings.module.ir.IrSettingsAccess,
        com.rheinmetal.tianshu.client.settings.module.ia.IaSettingsAccess,
        com.rheinmetal.tianshu.client.settings.module.presence.PresenceSettingsAccess,
        com.rheinmetal.tianshu.client.settings.module.asr.AsrSettingsAccess,
        com.rheinmetal.tianshu.client.settings.module.llm.LlmSettingsAccess,
        com.rheinmetal.tianshu.client.settings.module.tts.TtsSettingsAccess,
        com.rheinmetal.tianshu.client.config.ClientDiagnosticsConfiguration {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ASR_ENABLED;
    public static final ModConfigSpec.BooleanValue ASR_DIAGNOSTICS_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> SELECTED_MIC_NAME;
    public static final ModConfigSpec.ConfigValue<String> ASR_GITHUB_PROXY_URL;
    public static final ModConfigSpec.BooleanValue ASR_HIGH_PASS_FILTER_ENABLED;
    public static final ModConfigSpec.BooleanValue ASR_RNNOISE_ENABLED;
    public static final ModConfigSpec.BooleanValue ASR_VAD_ENABLED;
    public static final ModConfigSpec.BooleanValue TTS_ENABLED;
    public static final ModConfigSpec.BooleanValue TTS_DIAGNOSTICS_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> TTS_PREVIEW_TEXT;
    public static final ModConfigSpec.ConfigValue<String> TTS_GITHUB_PROXY_URL;
    public static final ModConfigSpec.BooleanValue LLM_ENABLED;
    public static final ModConfigSpec.BooleanValue LLM_DIAGNOSTICS_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> LLM_GPU_DEVICE_ID;
    public static final ModConfigSpec.BooleanValue LLM_FRAME_GUARD_ENABLED;
    public static final ModConfigSpec.IntValue LLM_FRAME_GUARD_TARGET_FPS;
    public static final ModConfigSpec.BooleanValue LLM_MTP_ENABLED;
    public static final ModConfigSpec.BooleanValue AX_ENABLED;
    public static final ModConfigSpec.BooleanValue AX_DIAGNOSTICS_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> AX_WAKE_WORD;
    public static final ModConfigSpec.BooleanValue AX_REPLY_SPEECH_ENABLED;
    public static final ModConfigSpec.BooleanValue AX_CHAT_THINKING_ENABLED;
    public static final ModConfigSpec.BooleanValue AX_INTERRUPT_ON_PLAYER_SPEECH;
    public static final ModConfigSpec.BooleanValue PRESENCE_HUD_ENABLED;
    public static final ModConfigSpec.BooleanValue PRESENCE_STATUS_TEXT_ENABLED;
    public static final ModConfigSpec.BooleanValue PRESENCE_ASR_STATUS_VISIBLE;
    public static final ModConfigSpec.BooleanValue PRESENCE_LLM_STATUS_VISIBLE;
    public static final ModConfigSpec.BooleanValue PRESENCE_TTS_STATUS_VISIBLE;
    public static final ModConfigSpec.BooleanValue PRESENCE_AX_STATUS_VISIBLE;
    public static final ModConfigSpec.BooleanValue PRESENCE_DEBUG_PIPELINE_ENABLED;
    public static final ModConfigSpec.BooleanValue IA_DIAGNOSTICS_ENABLED;
    public static final ModConfigSpec.BooleanValue IR_DIAGNOSTICS_ENABLED;
    public static final ModConfigSpec.BooleanValue AI_ENABLED;
    public static final ModConfigSpec.EnumValue<TriggerMode> TRIGGER_MODE;
    public static final ModConfigSpec.IntValue ASR_PORT;
    public static final ModConfigSpec.IntValue LLM_PORT;
    public static final ModConfigSpec.IntValue TTS_PORT;
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_ASR_NAME;
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_LLM_NAME;
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_TTS_NAME;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("天枢 AI - 核心设置").push("general");
        AI_ENABLED = builder.define("enabled", true);
        builder.pop();

        builder.comment("ASR 语音识别设置").push("asr");
        ASR_ENABLED = builder.define("enabled", true);
        ASR_DIAGNOSTICS_ENABLED = builder.define("diagnosticsEnabled", false);
        SELECTED_MIC_NAME = builder.define("selectedMicName", "");
        ASR_GITHUB_PROXY_URL = builder.define("githubProxyUrl", "https://gh-proxy.org/");
        TRIGGER_MODE = builder.defineEnum("triggerMode", TriggerMode.PUSH_TO_TALK);
        ASR_HIGH_PASS_FILTER_ENABLED = builder.define("highPassFilterEnabled", true);
        ASR_RNNOISE_ENABLED = builder.define("rnnoiseEnabled", false);
        ASR_VAD_ENABLED = builder.define("vadEnabled", false);
        builder.pop();

        builder.comment("TTS 语音播报设置").push("tts");
        TTS_ENABLED = builder.define("enabled", true);
        TTS_DIAGNOSTICS_ENABLED = builder.define("diagnosticsEnabled", false);
        TTS_PREVIEW_TEXT = builder.define("previewText", "这是一段天枢语音播报试听");
        TTS_GITHUB_PROXY_URL = builder.define("githubProxyUrl", "https://gh-proxy.org/");
        builder.pop();

        builder.comment("LLM 大语言模型设置").push("llm");
        LLM_ENABLED = builder.define("enabled", true);
        LLM_DIAGNOSTICS_ENABLED = builder.define("diagnosticsEnabled", false);
        LLM_GPU_DEVICE_ID = builder.define("gpuDeviceId", "");
        LLM_FRAME_GUARD_ENABLED = builder.define("frameGuardEnabled", true);
        LLM_FRAME_GUARD_TARGET_FPS = builder.defineInRange("frameGuardTargetFps", 60, 15, 240);
        LLM_MTP_ENABLED = builder.define("mtpEnabled", false);
        builder.pop();

        builder.comment("AX 辅星设置").push("ax");
        AX_ENABLED = builder.define("enabled", true);
        AX_DIAGNOSTICS_ENABLED = builder.define("diagnosticsEnabled", false);
        AX_WAKE_WORD = builder.define("wakeWord", "");
        AX_REPLY_SPEECH_ENABLED = builder.define("replySpeechEnabled", true);
        AX_CHAT_THINKING_ENABLED = builder.define("chatThinkingEnabled", false);
        AX_INTERRUPT_ON_PLAYER_SPEECH = builder.define("interruptOnPlayerSpeech", true);
        builder.pop();

        builder.comment("映迹 HUD 显示设置").push("presence");
        PRESENCE_HUD_ENABLED = builder.define("hudEnabled", true);
        PRESENCE_STATUS_TEXT_ENABLED = builder.define("statusTextEnabled", true);
        PRESENCE_ASR_STATUS_VISIBLE = builder.define("asrStatusVisible", true);
        PRESENCE_LLM_STATUS_VISIBLE = builder.define("llmStatusVisible", true);
        PRESENCE_TTS_STATUS_VISIBLE = builder.define("ttsStatusVisible", true);
        PRESENCE_AX_STATUS_VISIBLE = builder.define("axStatusVisible", true);
        PRESENCE_DEBUG_PIPELINE_ENABLED = builder.define("debugPipelineEnabled", false);
        builder.pop();

        builder.push("ia");
        IA_DIAGNOSTICS_ENABLED = builder.define("diagnosticsEnabled", false);
        builder.pop();
        builder.push("ir");
        IR_DIAGNOSTICS_ENABLED = builder.define("diagnosticsEnabled", false);
        builder.pop();

        builder.comment("底层服务设置（尽量不要修改）").push("internal");
        ASR_PORT = builder.defineInRange("asrPort", 18765, 1024, 65535);
        LLM_PORT = builder.defineInRange("llmPort", 18766, 1024, 65535);
        TTS_PORT = builder.defineInRange("ttsPort", 18767, 1024, 65535);
        CUSTOM_ASR_NAME = builder.define("customAsrName", "");
        CUSTOM_LLM_NAME = builder.define("customLlmName", "");
        CUSTOM_TTS_NAME = builder.define("customTtsName", "");
        builder.pop();

        SPEC = builder.build();
    }

    public boolean isAiEnabled() {
        return AI_ENABLED.get();
    }

    public void setAiEnabled(boolean enabled) {
        AI_ENABLED.set(enabled);
    }

    @Override
    public boolean isAsrEnabled() {
        return ASR_ENABLED.get();
    }

    public void setAsrEnabled(boolean enabled) {
        ASR_ENABLED.set(enabled);
    }

    public boolean isAsrDiagnosticsEnabled() {
        return ASR_DIAGNOSTICS_ENABLED.get();
    }

    public void setAsrDiagnosticsEnabled(boolean enabled) {
        ASR_DIAGNOSTICS_ENABLED.set(enabled);
    }

    @Override
    public TriggerMode getTriggerMode() {
        return TRIGGER_MODE.get();
    }

    public void setTriggerMode(TriggerMode mode) {
        TRIGGER_MODE.set(mode);
    }

    public int getAsrPort() {
        return ASR_PORT.get();
    }

    public int getLlmPort() {
        return LLM_PORT.get();
    }

    public int getTtsPort() {
        return TTS_PORT.get();
    }

    @Override
    public String getCustomAsrName() {
        String configured = CUSTOM_ASR_NAME.get();
        return normalizeAsrModelName(configured);
    }

    public void setCustomAsrName(String name) {
        CUSTOM_ASR_NAME.set(normalizeAsrModelName(name));
    }

    @Override
    public String getSelectedMicName() {
        return SELECTED_MIC_NAME.get();
    }

    public void setSelectedMicName(String name) {
        SELECTED_MIC_NAME.set(name == null ? "" : name);
    }

    public String getAsrGithubProxyUrl() {
        return ASR_GITHUB_PROXY_URL.get();
    }

    public void setAsrGithubProxyUrl(String url) {
        ASR_GITHUB_PROXY_URL.set(url == null ? "" : url.trim());
    }

    @Override
    public boolean isAsrRnnoiseEnabled() {
        return ASR_RNNOISE_ENABLED.get();
    }

    public void setAsrRnnoiseEnabled(boolean enabled) {
        ASR_RNNOISE_ENABLED.set(enabled);
    }

    @Override
    public boolean isAsrHighPassFilterEnabled() {
        return ASR_HIGH_PASS_FILTER_ENABLED.get();
    }

    public void setAsrHighPassFilterEnabled(boolean enabled) {
        ASR_HIGH_PASS_FILTER_ENABLED.set(enabled);
    }

    @Override
    public boolean isAsrVadEnabled() {
        return ASR_VAD_ENABLED.get();
    }

    public void setAsrVadEnabled(boolean enabled) {
        ASR_VAD_ENABLED.set(enabled);
    }

    @Override
    public boolean isTtsEnabled() {
        return TTS_ENABLED.get();
    }

    public void setTtsEnabled(boolean enabled) {
        TTS_ENABLED.set(enabled);
    }

    public boolean isTtsDiagnosticsEnabled() {
        return TTS_DIAGNOSTICS_ENABLED.get();
    }

    public void setTtsDiagnosticsEnabled(boolean enabled) {
        TTS_DIAGNOSTICS_ENABLED.set(enabled);
    }

    public String getTtsPreviewText() {
        return TTS_PREVIEW_TEXT.get();
    }

    public void setTtsPreviewText(String text) {
        TTS_PREVIEW_TEXT.set(text == null ? "" : text.trim());
    }

    public String getTtsGithubProxyUrl() {
        return TTS_GITHUB_PROXY_URL.get();
    }

    public void setTtsGithubProxyUrl(String url) {
        TTS_GITHUB_PROXY_URL.set(url == null ? "" : url.trim());
    }

    @Override
    public String getCustomLlmName() {
        return CUSTOM_LLM_NAME.get();
    }

    public void setCustomLlmName(String name) {
        CUSTOM_LLM_NAME.set(name);
    }

    @Override
    public boolean isLlmEnabled() {
        return LLM_ENABLED.get();
    }

    public void setLlmEnabled(boolean enabled) {
        LLM_ENABLED.set(enabled);
    }

    public boolean isLlmDiagnosticsEnabled() {
        return LLM_DIAGNOSTICS_ENABLED.get();
    }

    public void setLlmDiagnosticsEnabled(boolean enabled) {
        LLM_DIAGNOSTICS_ENABLED.set(enabled);
    }

    @Override
    public String getLlmGpuDeviceId() {
        return LLM_GPU_DEVICE_ID.get();
    }

    public void setLlmGpuDeviceId(String deviceId) {
        LLM_GPU_DEVICE_ID.set(deviceId == null ? "" : deviceId.trim());
    }

    @Override
    public boolean isLlmFrameGuardEnabled() {
        return LLM_FRAME_GUARD_ENABLED.get();
    }

    public void setLlmFrameGuardEnabled(boolean enabled) {
        LLM_FRAME_GUARD_ENABLED.set(enabled);
    }

    @Override
    public int getLlmFrameGuardTargetFps() {
        return LLM_FRAME_GUARD_TARGET_FPS.get();
    }

    public void setLlmFrameGuardTargetFps(int fps) {
        LLM_FRAME_GUARD_TARGET_FPS.set(Math.max(15, Math.min(240, fps)));
    }

    @Override
    public boolean isLlmMtpEnabled() {
        return LLM_MTP_ENABLED.get();
    }

    public void setLlmMtpEnabled(boolean enabled) {
        LLM_MTP_ENABLED.set(enabled);
    }

    @Override
    public boolean assistantEnabled() {
        return AX_ENABLED.get();
    }

    public void setAxEnabled(boolean enabled) {
        AX_ENABLED.set(enabled);
    }

    public boolean isAxDiagnosticsEnabled() {
        return AX_DIAGNOSTICS_ENABLED.get();
    }

    public void setAxDiagnosticsEnabled(boolean enabled) {
        AX_DIAGNOSTICS_ENABLED.set(enabled);
    }

    @Override
    public String wakeWord() {
        String value = AX_WAKE_WORD.get();
        return value == null ? "" : value.trim();
    }

    public void setAxWakeWord(String wakeWord) {
        AX_WAKE_WORD.set(wakeWord == null ? "" : wakeWord.trim());
    }

    @Override
    public AXOutputMode outputMode() {
        return AX_REPLY_SPEECH_ENABLED.get() ? AXOutputMode.TTS_ONLY : AXOutputMode.DISABLED;
    }

    public boolean isAxReplySpeechEnabled() {
        return AX_REPLY_SPEECH_ENABLED.get();
    }

    public void setAxReplySpeechEnabled(boolean enabled) {
        AX_REPLY_SPEECH_ENABLED.set(enabled);
    }

    @Override
    public boolean chatThinkingEnabled() {
        return AX_CHAT_THINKING_ENABLED.get();
    }

    public void setAxChatThinkingEnabled(boolean enabled) {
        AX_CHAT_THINKING_ENABLED.set(enabled);
    }

    @Override
    public boolean interruptOnPlayerSpeech() {
        return AX_INTERRUPT_ON_PLAYER_SPEECH.get();
    }

    public void setAxInterruptOnPlayerSpeech(boolean enabled) {
        AX_INTERRUPT_ON_PLAYER_SPEECH.set(enabled);
    }

    public boolean isPresenceHudEnabled() {
        return PRESENCE_HUD_ENABLED.get();
    }

    public void setPresenceHudEnabled(boolean enabled) {
        PRESENCE_HUD_ENABLED.set(enabled);
    }

    public boolean isPresenceStatusTextEnabled() {
        return PRESENCE_STATUS_TEXT_ENABLED.get();
    }

    public void setPresenceStatusTextEnabled(boolean enabled) {
        PRESENCE_STATUS_TEXT_ENABLED.set(enabled);
    }

    public boolean isPresenceAsrStatusVisible() {
        return PRESENCE_ASR_STATUS_VISIBLE.get();
    }

    public void setPresenceAsrStatusVisible(boolean visible) {
        PRESENCE_ASR_STATUS_VISIBLE.set(visible);
    }

    public boolean isPresenceLlmStatusVisible() {
        return PRESENCE_LLM_STATUS_VISIBLE.get();
    }

    public void setPresenceLlmStatusVisible(boolean visible) {
        PRESENCE_LLM_STATUS_VISIBLE.set(visible);
    }

    public boolean isPresenceTtsStatusVisible() {
        return PRESENCE_TTS_STATUS_VISIBLE.get();
    }

    public void setPresenceTtsStatusVisible(boolean visible) {
        PRESENCE_TTS_STATUS_VISIBLE.set(visible);
    }

    public boolean isPresenceAxStatusVisible() {
        return PRESENCE_AX_STATUS_VISIBLE.get();
    }

    public void setPresenceAxStatusVisible(boolean visible) {
        PRESENCE_AX_STATUS_VISIBLE.set(visible);
    }

    public boolean isPresenceDebugPipelineEnabled() {
        return PRESENCE_DEBUG_PIPELINE_ENABLED.get();
    }

    public void setPresenceDebugPipelineEnabled(boolean enabled) {
        PRESENCE_DEBUG_PIPELINE_ENABLED.set(enabled);
    }

    public boolean isIaDiagnosticsEnabled() {
        return IA_DIAGNOSTICS_ENABLED.get();
    }

    public void setIaDiagnosticsEnabled(boolean enabled) {
        IA_DIAGNOSTICS_ENABLED.set(enabled);
    }

    public boolean isIrDiagnosticsEnabled() {
        return IR_DIAGNOSTICS_ENABLED.get();
    }

    public void setIrDiagnosticsEnabled(boolean enabled) {
        IR_DIAGNOSTICS_ENABLED.set(enabled);
    }

    @Override
    public String getCustomTtsName() {
        return CUSTOM_TTS_NAME.get();
    }

    public void setCustomTtsName(String name) {
        CUSTOM_TTS_NAME.set(name);
    }

    public Path getRootPath() {
        return getGameConfigDir().resolve("module");
    }

    @Override
    public Path storageRoot() {
        return getRootPath().resolve("ax").resolve("cache");
    }

    public Path getGameConfigDir() {
        return Paths.get(Minecraft.getInstance().gameDirectory.getAbsolutePath(), "config/Tianshu");
    }

    @Override
    public Path getAsrBasePath() {
        return getRootPath().resolve("asr");
    }

    @Override
    public Path getLlmBasePath() {
        return getRootPath().resolve("llm");
    }

    @Override
    public Path getTtsBasePath() {
        return getRootPath().resolve("tts");
    }

    @Override
    public Path getVoiceLibraryPath() {
        return getTtsBasePath().resolve("voices");
    }

    @Override
    public Path getAsrModelPath() {
        String name = getCustomAsrName();
        if (name == null || name.isBlank()) {
            return null;
        }
        return getAsrBasePath().resolve("model").resolve(name.trim());
    }

    private String normalizeAsrModelName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        AsrModelInfo info = AsrModelManager.getModelByLocalKey(name.trim());
        return info == null ? "" : info.localKey();
    }

    @Override
    public String getLlmEmbeddingModelName() {
        com.rheinmetal.tianshu.model.LlmModelInfo embedding = com.rheinmetal.tianshu.model.LlmModelManager.getDefaultEmbeddingModel(getClientLanguageTag());
        return embedding != null ? embedding.name : "";
    }

    private String getClientLanguageTag() {
        try {
            String code = Minecraft.getInstance().getLanguageManager().getSelected();
            if (code != null && !code.isBlank()) {
                return code.split("[_-]", 2)[0].toLowerCase(java.util.Locale.ROOT);
            }
        } catch (Exception ignored) {
        }
        return java.util.Locale.getDefault().getLanguage();
    }

    public void save() {
        SPEC.save();
    }
}
