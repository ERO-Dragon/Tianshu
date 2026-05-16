package com.rheinmetal.tianshu.config;

import com.rheinmetal.tianshu.constant.TriggerMode;
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
    }
}
