package com.rheinmetal.tianshu.config;

import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.core.FeatureManager;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ClientConfig implements com.rheinmetal.tianshu.api.ITianshuConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ASR_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> SELECTED_MIC_NAME;
    public static final ModConfigSpec.ConfigValue<String> ASR_GITHUB_PROXY_URL;
    public static final ModConfigSpec.BooleanValue ASR_RNNOISE_ENABLED;
    public static final ModConfigSpec.BooleanValue ASR_VAD_ENABLED;
    public static final ModConfigSpec.BooleanValue TTS_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> TTS_PREVIEW_TEXT;
    public static final ModConfigSpec.ConfigValue<String> TTS_GITHUB_PROXY_URL;
    public static final ModConfigSpec.BooleanValue AI_ENABLED;
    public static final ModConfigSpec.EnumValue<TriggerMode> TRIGGER_MODE;
    public static final ModConfigSpec.ConfigValue<String> WAKE_WORD;
    public static final ModConfigSpec.IntValue ASR_PORT;
    public static final ModConfigSpec.IntValue LLM_PORT;
    public static final ModConfigSpec.IntValue TTS_PORT;
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_ASR_NAME;
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_LLM_NAME;
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_TTS_NAME;

    public static final ModConfigSpec.BooleanValue TACTICAL_RADAR_ENABLED;
    public static final ModConfigSpec.BooleanValue NAVIGATION_ENABLED;
    public static final ModConfigSpec.BooleanValue RECIPE_PANEL_ENABLED;
    public static final ModConfigSpec.BooleanValue AUDIO_RADAR_ENABLED;
    public static final ModConfigSpec.BooleanValue COMPANION_CARD_ENABLED;
    public static final ModConfigSpec.BooleanValue DURABILITY_ALERT_ENABLED;
    public static final ModConfigSpec.BooleanValue CHAT_ASSISTANT;
    public static final ModConfigSpec.BooleanValue TACTICAL_MR_ENABLED;
    public static final ModConfigSpec.DoubleValue TACTICAL_MR_CARD_DAMPING;
    public static final ModConfigSpec.DoubleValue TACTICAL_MR_CARD_MIN_DAMPING;
    public static final ModConfigSpec.DoubleValue TACTICAL_MR_CARD_MAX_DAMPING;
    public static final ModConfigSpec.DoubleValue TACTICAL_MR_CARD_MIN_SCALE;
    public static final ModConfigSpec.DoubleValue TACTICAL_MR_CARD_MAX_SCALE;
    public static final ModConfigSpec.DoubleValue TACTICAL_MR_SEGMENT_LENGTH;
    public static final ModConfigSpec.DoubleValue TACTICAL_MR_DAY_ALPHA;
    public static final ModConfigSpec.DoubleValue TACTICAL_MR_NIGHT_ALPHA;
    public static final ModConfigSpec.DoubleValue CRAFTING_GRAPH_ALPHA;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("天枢 AI - 核心设置").push("general");
        AI_ENABLED = builder.define("enabled", true);
        builder.pop();

        builder.comment("ASR 语音识别设置").push("asr");
        ASR_ENABLED = builder.define("enabled", true);
        SELECTED_MIC_NAME = builder.define("selectedMicName", "");
        ASR_GITHUB_PROXY_URL = builder.define("githubProxyUrl", "https://gh-proxy.org/");
        TRIGGER_MODE = builder.defineEnum("triggerMode", TriggerMode.PUSH_TO_TALK);
        WAKE_WORD = builder.define("wakeWord", "天枢");
        ASR_RNNOISE_ENABLED = builder.define("rnnoiseEnabled", false);
        ASR_VAD_ENABLED = builder.define("vadEnabled", false);
        builder.pop();

        builder.comment("TTS 语音播报设置").push("tts");
        TTS_ENABLED = builder.define("enabled", true);
        TTS_PREVIEW_TEXT = builder.define("previewText", "这是一段天枢语音播报试听");
        TTS_GITHUB_PROXY_URL = builder.define("githubProxyUrl", "https://gh-proxy.org/");
        builder.pop();

        builder.comment("功能开关（纯客户端）").push("features");
        TACTICAL_RADAR_ENABLED = builder.comment("战术 MR 系统")
                .define("tacticalRadar", true);
        NAVIGATION_ENABLED = builder.comment("导航 HUD")
                .define("navigation", true);
        RECIPE_PANEL_ENABLED = builder.comment("左侧合成图谱工作台")
                .define("recipePanel", true);
        CRAFTING_GRAPH_ALPHA = builder.comment("合成图谱整体透明度倍率，范围 0.2 到 1.0")
                .defineInRange("craftingGraphAlpha", 0.86, 0.2, 1.0);
        AUDIO_RADAR_ENABLED = builder.comment("听觉预警雷达")
                .define("audioRadar", true);
        COMPANION_CARD_ENABLED = builder.comment("智能伴生卡片")
                .define("companionCard", true);
        DURABILITY_ALERT_ENABLED = builder.comment("关键链路熔断预警系统")
                .define("durabilityAlert", true);
        CHAT_ASSISTANT = builder.comment("聊天助手（同声传译与语音发送）")
                .define("chatAssistant", true);
        TACTICAL_MR_ENABLED = builder.comment("全息战术 MR 系统")
                .define("tacticalMr", true);
        TACTICAL_MR_CARD_DAMPING = builder.comment("全息战术 MR 卡片与 C 点的基础跟随系数，越小近距离移动越慢越稳；卡片被拉远后会自动提高跟随速度，范围 0.05 到 0.8")
                .defineInRange("tacticalMrCardDamping", 0.22, 0.05, 0.8);
        TACTICAL_MR_CARD_MIN_DAMPING = builder.comment("全息战术 MR 卡片与 C 点动态跟随的最小系数，控制短距离移动的慢速稳定程度，范围 0.01 到 0.8")
                .defineInRange("tacticalMrCardMinDamping", 0.05, 0.01, 0.8);
        TACTICAL_MR_CARD_MAX_DAMPING = builder.comment("全息战术 MR 卡片与 C 点动态跟随的最大系数，控制远距离被拉开后的追赶速度，范围 0.05 到 0.95")
                .defineInRange("tacticalMrCardMaxDamping", 0.75, 0.05, 0.95);
        TACTICAL_MR_CARD_MIN_SCALE = builder.comment("全息战术 MR 卡片距离缩放的最小倍率，控制远处卡片最小能缩到多小，范围 0.1 到 4.0")
                .defineInRange("tacticalMrCardMinScale", 0.4, 0.1, 4.0);
        TACTICAL_MR_CARD_MAX_SCALE = builder.comment("全息战术 MR 卡片距离缩放的最大倍率，控制近处卡片最大能放到多大，范围 0.1 到 4.0")
                .defineInRange("tacticalMrCardMaxScale", 1.5, 0.1, 4.0);
        TACTICAL_MR_SEGMENT_LENGTH = builder.comment("全息战术 MR A-B 固定线段的基础长度，实际显示会随距离缩放倍率一起变化，范围 8 到 160")
                .defineInRange("tacticalMrSegmentLength", 40.0, 8.0, 160.0);
        TACTICAL_MR_DAY_ALPHA = builder.comment("全息战术 MR 白天透明度倍率，数值越大越亮越明显，范围 0.05 到 1.5")
                .defineInRange("tacticalMrDayAlpha", 1.0, 0.05, 1.5);
        TACTICAL_MR_NIGHT_ALPHA = builder.comment("全息战术 MR 夜晚透明度倍率，黄昏后会从白天倍率过渡到该值，范围 0.05 到 1.5")
                .defineInRange("tacticalMrNightAlpha", 0.55, 0.05, 1.5);
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

    @Override
    public boolean isAiEnabled() {
        return AI_ENABLED.get();
    }

    @Override
    public void setAiEnabled(boolean enabled) {
        AI_ENABLED.set(enabled);
    }

    @Override
    public boolean isAsrEnabled() {
        return ASR_ENABLED.get();
    }

    @Override
    public void setAsrEnabled(boolean enabled) {
        ASR_ENABLED.set(enabled);
    }

    @Override
    public TriggerMode getTriggerMode() {
        return TRIGGER_MODE.get();
    }

    @Override
    public void setTriggerMode(TriggerMode mode) {
        TRIGGER_MODE.set(mode);
    }

    @Override
    public String getWakeWord() {
        return WAKE_WORD.get();
    }

    @Override
    public void setWakeWord(String word) {
        WAKE_WORD.set(word);
    }

    @Override
    public int getAsrPort() {
        return ASR_PORT.get();
    }

    @Override
    public int getLlmPort() {
        return LLM_PORT.get();
    }

    @Override
    public int getTtsPort() {
        return TTS_PORT.get();
    }

    @Override
    public String getCustomAsrName() {
        return CUSTOM_ASR_NAME.get();
    }

    @Override
    public void setCustomAsrName(String name) {
        CUSTOM_ASR_NAME.set(name);
    }

    @Override
    public String getSelectedMicName() {
        return SELECTED_MIC_NAME.get();
    }

    @Override
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

    @Override
    public void setAsrRnnoiseEnabled(boolean enabled) {
        ASR_RNNOISE_ENABLED.set(enabled);
    }

    @Override
    public boolean isAsrVadEnabled() {
        return ASR_VAD_ENABLED.get();
    }

    @Override
    public void setAsrVadEnabled(boolean enabled) {
        ASR_VAD_ENABLED.set(enabled);
    }

    @Override
    public boolean isTtsEnabled() {
        return TTS_ENABLED.get();
    }

    @Override
    public void setTtsEnabled(boolean enabled) {
        TTS_ENABLED.set(enabled);
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

    @Override
    public void setCustomLlmName(String name) {
        CUSTOM_LLM_NAME.set(name);
    }

    @Override
    public String getCustomTtsName() {
        return CUSTOM_TTS_NAME.get();
    }

    @Override
    public void setCustomTtsName(String name) {
        CUSTOM_TTS_NAME.set(name);
    }

    @Override
    public Path getRootPath() {
        return Paths.get(Minecraft.getInstance().gameDirectory.getAbsolutePath(), "config/TianshuAIAssistant").resolve("module");
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
        return getAsrBasePath().resolve("model").resolve(name != null && !name.isBlank() ? name : "Zipformer");
    }

    @Override
    public Path getLlmModelPath() {
        String name = getCustomLlmName();
        return getLlmBasePath().resolve("model").resolve(name != null && !name.isBlank() ? name.trim() : getDefaultLlmModelName());
    }

    @Override
    public Path getTtsModelPath() {
        String name = getCustomTtsName();
        return getTtsBasePath().resolve("model").resolve(name != null && !name.isBlank() ? name : "vits-zh-hf-keqing");
    }

    @Override
    public Path getLlmGgufFilePath() {
        String modelName = getCustomLlmName();
        if (modelName == null || modelName.isBlank()) {
            modelName = getDefaultLlmModelName();
        }
        modelName = modelName.trim();
        Path modelDir = getLlmBasePath().resolve("model").resolve(modelName);

        if (modelName.toLowerCase().endsWith(".gguf")) {
            return getLlmBasePath().resolve("model").resolve(modelName);
        }
        if (Files.isDirectory(modelDir)) {
            com.rheinmetal.tianshu.model.LlmModelInfo catalogInfo = com.rheinmetal.tianshu.model.LlmModelManager.getModelByName(modelName);
            if (catalogInfo != null) {
                Path catalogFile = modelDir.resolve(catalogInfo.getModelFile());
                if (Files.isRegularFile(catalogFile)) {
                    return catalogFile;
                }
            }
            Path preferred = modelDir.resolve("model.gguf");
            if (Files.isRegularFile(preferred)) {
                return preferred;
            }
            try (var stream = Files.list(modelDir)) {
                Path ggufFile = stream
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".gguf"))
                    .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
                if (ggufFile != null) {
                    return ggufFile;
                }
            } catch (IOException e) {
            }
        }
        com.rheinmetal.tianshu.model.LlmModelInfo catalogInfo = com.rheinmetal.tianshu.model.LlmModelManager.getModelByName(modelName);
        return modelDir.resolve(catalogInfo != null ? catalogInfo.getModelFile() : "model.gguf");
    }

    public boolean isTacticalRadarEnabled() {
        return TACTICAL_RADAR_ENABLED.get();
    }

    public void setTacticalRadarEnabled(boolean enabled) {
        TACTICAL_RADAR_ENABLED.set(enabled);
    }

    public boolean isNavigationEnabled() {
        return NAVIGATION_ENABLED.get();
    }

    public void setNavigationEnabled(boolean enabled) {
        NAVIGATION_ENABLED.set(enabled);
    }

    public boolean isRecipePanelEnabled() {
        return RECIPE_PANEL_ENABLED.get();
    }

    public void setRecipePanelEnabled(boolean enabled) {
        RECIPE_PANEL_ENABLED.set(enabled);
    }

    public boolean isAudioRadarEnabled() {
        return AUDIO_RADAR_ENABLED.get();
    }

    public void setAudioRadarEnabled(boolean enabled) {
        AUDIO_RADAR_ENABLED.set(enabled);
    }

    public boolean isCompanionCardEnabled() {
        return COMPANION_CARD_ENABLED.get();
    }

    public void setCompanionCardEnabled(boolean enabled) {
        COMPANION_CARD_ENABLED.set(enabled);
    }

    public boolean isDurabilityAlertEnabled() {
        return DURABILITY_ALERT_ENABLED.get();
    }

    public void setDurabilityAlertEnabled(boolean enabled) {
        DURABILITY_ALERT_ENABLED.set(enabled);
    }

    public static void syncToFeatureManager() {
        FeatureManager.syncFromClientConfig(
                AI_ENABLED.get(),
                TACTICAL_RADAR_ENABLED.get(),
                NAVIGATION_ENABLED.get(),
                RECIPE_PANEL_ENABLED.get(),
                AUDIO_RADAR_ENABLED.get(),
                COMPANION_CARD_ENABLED.get(),
                DURABILITY_ALERT_ENABLED.get(),
                CHAT_ASSISTANT.get(),
                TACTICAL_MR_ENABLED.get()
        );
    }

    public boolean isTacticalMrEnabled() {
        return TACTICAL_MR_ENABLED.get();
    }

    public void setTacticalMrEnabled(boolean enabled) {
        TACTICAL_MR_ENABLED.set(enabled);
    }

    @Override
    public String getLlmEmbeddingModelName() {
        com.rheinmetal.tianshu.model.LlmModelInfo embedding = com.rheinmetal.tianshu.model.LlmModelManager.getDefaultEmbeddingModel(getClientLanguageTag());
        return embedding != null ? embedding.name : "";
    }

    @Override
    public int getLlmContextSize() {
        String modelName = getCustomLlmName();
        if (modelName != null && !modelName.isBlank()) {
            com.rheinmetal.tianshu.model.LlmModelInfo info = com.rheinmetal.tianshu.model.LlmModelManager.getModelByName(modelName.trim());
            if (info != null) return info.getContextSize();
        }
        com.rheinmetal.tianshu.model.LlmModelInfo defaultModel = getDefaultLlmModelInfo();
        return defaultModel != null ? defaultModel.getContextSize() : 4096;
    }

    @Override
    public int getLlmChatContextSize() {
        return getLlmContextSize();
    }

    @Override
    public int getLlmTaskContextSize() {
        return Math.max(getLlmContextSize(), 8192);
    }

    @Override
    public int getLlmEmbeddingContextSize() {
        com.rheinmetal.tianshu.model.LlmModelInfo embedding = com.rheinmetal.tianshu.model.LlmModelManager.getDefaultEmbeddingModel(getClientLanguageTag());
        return embedding != null ? embedding.getContextSize() : 4096;
    }

    private String getDefaultLlmModelName() {
        com.rheinmetal.tianshu.model.LlmModelInfo info = getDefaultLlmModelInfo();
        return info != null && info.name != null && !info.name.isBlank() ? info.name : "model";
    }

    private com.rheinmetal.tianshu.model.LlmModelInfo getDefaultLlmModelInfo() {
        java.util.List<com.rheinmetal.tianshu.model.LlmModelInfo> catalog = com.rheinmetal.tianshu.model.LlmModelManager.getAllModels();
        return catalog.isEmpty() ? null : catalog.get(0);
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

    @Override
    public void save() {
        SPEC.save();
        syncToFeatureManager();
    }
}
