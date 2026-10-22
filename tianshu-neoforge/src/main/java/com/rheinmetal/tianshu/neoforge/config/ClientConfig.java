package com.rheinmetal.tianshu.neoforge.config;

import com.rheinmetal.tianshu.function.auxilium.AXAssistantSettings;
import com.rheinmetal.tianshu.function.asr.settings.AsrConfiguration;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputMode;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageConfiguration;
import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.llm.settings.LlmContextTiers;
import com.rheinmetal.tianshu.function.llm.settings.LlmModelEntry;
import com.rheinmetal.tianshu.function.llm.settings.LlmModelSettings;
import com.rheinmetal.tianshu.function.tts.settings.TtsConfiguration;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.AsrModelManager;
import com.rheinmetal.tianshu.client.presence.hud.PresenceHudVisualPreset;
import com.rheinmetal.tianshu.client.presence.hud.PresenceHudSettings;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceConfiguration;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public class ClientConfig implements AsrConfiguration, LlmConfiguration, TtsConfiguration,
        AXStorageConfiguration, VoiceResourceConfiguration, AXAssistantSettings, AXOutputSettings,
        com.rheinmetal.tianshu.client.settings.module.ax.AxSettingsAccess,
        com.rheinmetal.tianshu.client.settings.module.presence.PresenceSettingsAccess,
        com.rheinmetal.tianshu.client.settings.module.asr.AsrSettingsAccess,
        com.rheinmetal.tianshu.client.settings.module.llm.LlmSettingsAccess,
        com.rheinmetal.tianshu.client.settings.module.tts.TtsSettingsAccess,
        com.rheinmetal.tianshu.client.settings.global.GlobalDebugSettingsAccess {

    private final Path gameConfigDirectory;
    private final Supplier<String> languageCodeSupplier;

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue DEBUG_ENABLED;
    public static final ModConfigSpec.BooleanValue ASR_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> SELECTED_MIC_NAME;
    public static final ModConfigSpec.ConfigValue<String> ASR_GITHUB_PROXY_URL;
    public static final ModConfigSpec.BooleanValue ASR_HIGH_PASS_FILTER_ENABLED;
    public static final ModConfigSpec.BooleanValue ASR_RNNOISE_ENABLED;
    public static final ModConfigSpec.BooleanValue TTS_ENABLED;
    public static final ModConfigSpec.BooleanValue TTS_STREAMING_SYNTHESIS;
    public static final ModConfigSpec.ConfigValue<String> TTS_PREVIEW_TEXT;
    public static final ModConfigSpec.ConfigValue<String> TTS_GITHUB_PROXY_URL;
    public static final ModConfigSpec.BooleanValue LLM_ENABLED;
    /*
     * Legacy single-model keys. They stay readable and writable so an old file keeps migrating and a
     * downgrade keeps working; the model-list keys below take over once they are written.
     */
    public static final ModConfigSpec.ConfigValue<String> LLM_REMOTE_BASE_URL;
    public static final ModConfigSpec.ConfigValue<String> LLM_REMOTE_API_KEY;
    public static final ModConfigSpec.ConfigValue<String> LLM_REMOTE_MODEL;
    /** One entry per line; empty means the legacy keys above are still in charge. */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> LLM_MODELS;
    public static final ModConfigSpec.ConfigValue<String> LLM_ACTIVE_MODEL_ID;
    /** One {@code id=key} per line. A separate key on purpose: never part of the model list. */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> LLM_CREDENTIALS;
    public static final ModConfigSpec.IntValue LLM_CONTEXT_WINDOW_TOKENS;
    public static final ModConfigSpec.IntValue LLM_CONTEXT_TIER;
    /** Embedding position: 999 puts every layer on the card, 0 — the default — keeps the lane on the CPU. */
    public static final ModConfigSpec.IntValue LLM_EMBEDDING_GPU_LAYERS;
    /** Runtime window of the embedding context; the model's own ceiling is applied on top. */
    public static final ModConfigSpec.IntValue LLM_EMBEDDING_CONTEXT_SIZE;
    public static final ModConfigSpec.DoubleValue LLM_USAGE_CACHE_MISS_INPUT_PRICE_PER_MILLION;
    public static final ModConfigSpec.DoubleValue LLM_USAGE_CACHE_HIT_INPUT_PRICE_PER_MILLION;
    public static final ModConfigSpec.DoubleValue LLM_USAGE_OUTPUT_PRICE_PER_MILLION;
    public static final ModConfigSpec.BooleanValue AX_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> AX_WAKE_WORD;
    public static final ModConfigSpec.BooleanValue AX_REPLY_SPEECH_ENABLED;
    public static final ModConfigSpec.BooleanValue AX_CHAT_THINKING_ENABLED;
    public static final ModConfigSpec.BooleanValue AX_ALLOW_INTERRUPTION;
    public static final ModConfigSpec.BooleanValue PRESENCE_HUD_ENABLED;
    public static final ModConfigSpec.BooleanValue PRESENCE_STATUS_TEXT_ENABLED;
    public static final ModConfigSpec.BooleanValue PRESENCE_ICON_ENABLED;
    public static final ModConfigSpec.DoubleValue PRESENCE_ICON_SIZE_PIXELS;
    public static final ModConfigSpec.EnumValue<PresenceHudVisualPreset> PRESENCE_VISUAL_PRESET;
    public static final ModConfigSpec.DoubleValue PRESENCE_ICON_POSITION_X;
    public static final ModConfigSpec.DoubleValue PRESENCE_ICON_POSITION_Y;
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_ASR_NAME;
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_LLM_NAME;
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_TTS_NAME;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("debug");
        DEBUG_ENABLED = builder.define("enabled", false);
        builder.pop();

        builder.comment("ASR 语音识别设置").push("asr");
        ASR_ENABLED = builder.define("enabled", true);
        SELECTED_MIC_NAME = builder.define("selectedMicName", "");
        ASR_GITHUB_PROXY_URL = builder.define("githubProxyUrl", "https://gh-proxy.org/");
        ASR_HIGH_PASS_FILTER_ENABLED = builder.define("highPassFilterEnabled", true);
        ASR_RNNOISE_ENABLED = builder.define("rnnoiseEnabled", false);
        builder.pop();

        builder.push("tts");
        TTS_ENABLED = builder.define("enabled", true);
        TTS_STREAMING_SYNTHESIS = builder.define("streamingSynthesis", true);
        TTS_PREVIEW_TEXT = builder.define("previewText", "");
        TTS_GITHUB_PROXY_URL = builder.define("githubProxyUrl", "https://gh-proxy.org/");
        builder.pop();

        builder.comment("LLM 大语言模型设置").push("llm");
        LLM_ENABLED = builder.define("enabled", true);
        builder.comment("旧版单模型键（保持原位置，旧配置继续可读）。模型列表为空时，读取时由这三项加 contextWindowTokens"
                + " 合成一条模型条目，不丢 key，且重复执行结果一致。");
        LLM_REMOTE_BASE_URL = builder.define("remoteBaseUrl", LlmConfiguration.DEFAULT_LLM_REMOTE_BASE_URL);
        LLM_REMOTE_API_KEY = builder.define("remoteApiKey", "");
        LLM_REMOTE_MODEL = builder.define("remoteModel", LlmConfiguration.DEFAULT_LLM_REMOTE_MODEL);
        LLM_MODELS = builder.comment("模型与模型目录，每行一个条目，两种行：",
                        "@entry|id|displayName|providerId|baseUrl|activeModelId —— 一个提供商，activeModelId 是它当前使用的模型",
                        "@model|entryId|modelId|displayName|contextWindowTokens —— 该提供商的一个模型",
                        "旧格式 id|displayName|providerId|baseUrl|modelId|contextWindowTokens 仍可读取，",
                        "会按“只有一个模型的提供商”读入，再次写出时升级为上面的新格式（读两次结果一致）。",
                        "带 vision 列的旧 @model 行同样可读，该列被忽略。",
                        "留空表示沿用上面的单模型键（读取时合成，写入旧键即可）。")
                .defineListAllowEmpty("models", List.<String>of(), () -> "", element -> element instanceof String);
        LLM_ACTIVE_MODEL_ID = builder.comment("当前默认模型的 id；列表非空时必须是列表里的 id，否则取第一条。")
                .define("activeModelId", LlmModelEntry.LEGACY_ID);
        LLM_CREDENTIALS = builder.comment("模型凭据，每行 id=apiKey。故意与模型列表分开存放：条目本身不带 key，"
                        + "列表序列化或打日志都不可能带出凭据。任何日志都不得输出本节内容。")
                .defineListAllowEmpty("credentials", List.<String>of(), () -> "", element -> element instanceof String);
        LLM_CONTEXT_WINDOW_TOKENS = builder.defineInRange(
                "contextWindowTokens",
                LlmConfiguration.DEFAULT_LLM_CONTEXT_WINDOW_TOKENS,
                LlmContextTiers.minimumTier(),
                LlmConfiguration.MAX_LLM_CONTEXT_WINDOW_TOKENS);
        LLM_CONTEXT_TIER = builder.defineInRange(
                "contextTier",
                LlmContextTiers.DEFAULT_TIER,
                LlmContextTiers.TIER_50K,
                LlmContextTiers.TIER_500K);
        LLM_EMBEDDING_GPU_LAYERS = builder.comment("向量模型载入位置：999 表示全部层放到 GPU，0 表示只用 CPU（默认）。",
                        "默认 CPU：同一进程内 embedding 的 Vulkan 后端与游戏的 OpenGL 渲染并存，在部分 NVIDIA 驱动上不稳定"
                                + "（曾观察到 vk::Device::createComputePipeline: ErrorUnknown 与进世界后的 GL_OUT_OF_MEMORY）。",
                        "这与显存余量无关：崩溃时显存仍有富余。确认稳定的显卡可以手动切到 GPU。",
                        "GPU 载入失败时运行时会自动回落到 CPU，本键的值不变（改回 GPU 需要重新加载一次）。")
                .defineInRange("embeddingGpuLayers",
                        LlmConfiguration.EMBEDDING_GPU_LAYERS_CPU,
                        LlmConfiguration.EMBEDDING_GPU_LAYERS_CPU,
                        LlmConfiguration.EMBEDDING_GPU_LAYERS_ALL);
        LLM_EMBEDDING_CONTEXT_SIZE = builder.comment("向量模型的上下文长度（token），直接决定 KV cache 的原生内存占用。",
                        "RAG 的实际输入只有一句事件（一两百字）和几百字的摘要，该值只需覆盖最长的摘要；",
                        "改大不会提升召回，只会成倍吃掉内存。模型自身的上限仍然生效（此处设得比模型上限大时按模型上限）。")
                .defineInRange("embeddingContextSize",
                        LlmConfiguration.DEFAULT_EMBEDDING_CONTEXT_SIZE,
                        LlmConfiguration.MIN_EMBEDDING_CONTEXT_SIZE,
                        LlmConfiguration.MAX_EMBEDDING_CONTEXT_SIZE);
        builder.comment("Token prices in CNY per million tokens, used to turn recorded usage into a local estimate."
                        + " 0 hides the estimate. These prices never produce an authoritative amount: the provider's"
                        + " own account answer is what the HUD shows as accurate, and this estimate is reconciliation only.")
                .push("usagePrice");
        LLM_USAGE_CACHE_MISS_INPUT_PRICE_PER_MILLION = builder.defineInRange(
                "cacheMissInputPerMillion",
                LlmConfiguration.DEFAULT_LLM_USAGE_CACHE_MISS_INPUT_PRICE_PER_MILLION,
                LlmConfiguration.MIN_LLM_USAGE_PRICE_PER_MILLION,
                LlmConfiguration.MAX_LLM_USAGE_PRICE_PER_MILLION);
        LLM_USAGE_CACHE_HIT_INPUT_PRICE_PER_MILLION = builder.defineInRange(
                "cacheHitInputPerMillion",
                LlmConfiguration.DEFAULT_LLM_USAGE_CACHE_HIT_INPUT_PRICE_PER_MILLION,
                LlmConfiguration.MIN_LLM_USAGE_PRICE_PER_MILLION,
                LlmConfiguration.MAX_LLM_USAGE_PRICE_PER_MILLION);
        LLM_USAGE_OUTPUT_PRICE_PER_MILLION = builder.defineInRange(
                "outputPerMillion",
                LlmConfiguration.DEFAULT_LLM_USAGE_OUTPUT_PRICE_PER_MILLION,
                LlmConfiguration.MIN_LLM_USAGE_PRICE_PER_MILLION,
                LlmConfiguration.MAX_LLM_USAGE_PRICE_PER_MILLION);
        builder.pop();
        builder.pop();

        builder.comment("AX 辅星设置").push("ax");
        AX_ENABLED = builder.define("enabled", true);
        AX_WAKE_WORD = builder.define("wakeWord", "");
        AX_REPLY_SPEECH_ENABLED = builder.define("replySpeechEnabled", true);
        AX_CHAT_THINKING_ENABLED = builder.define("chatThinkingEnabled", false);
        AX_ALLOW_INTERRUPTION = builder.define("allowInterruption", true);
        builder.pop();

        builder.push("presence");
        PRESENCE_HUD_ENABLED = builder.define("hudEnabled", true);
        PRESENCE_STATUS_TEXT_ENABLED = builder.define("statusTextEnabled", true);
        PRESENCE_ICON_ENABLED = builder.define("iconEnabled", true);
        PRESENCE_ICON_SIZE_PIXELS = builder.defineInRange("iconSizePixels", PresenceHudSettings.DEFAULT_ICON_SIZE_PIXELS,
                PresenceHudSettings.MIN_ICON_SIZE_PIXELS, PresenceHudSettings.MAX_ICON_SIZE_PIXELS);
        PRESENCE_VISUAL_PRESET = builder.defineEnum("visualPreset", PresenceHudVisualPreset.PRESET_ONE);
        PRESENCE_ICON_POSITION_X = builder.defineInRange("iconPositionX", 0.5D, 0.0D, 1.0D);
        PRESENCE_ICON_POSITION_Y = builder.defineInRange("iconPositionY", -1.0D, -1.0D, 1.0D);
        builder.pop();

        builder.comment("底层服务设置（尽量不要修改）").push("internal");
        CUSTOM_ASR_NAME = builder.define("customAsrName", "");
        CUSTOM_LLM_NAME = builder.define("customLlmName", "");
        CUSTOM_TTS_NAME = builder.define("customTtsName", "");
        builder.pop();

        SPEC = builder.build();
    }

    public ClientConfig(Path gameDirectory, Supplier<String> languageCodeSupplier) {
        this.gameConfigDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory")
                .toAbsolutePath()
                .normalize()
                .resolve("config")
                .resolve("Tianshu");
        this.languageCodeSupplier = Objects.requireNonNull(languageCodeSupplier, "languageCodeSupplier");
    }

    @Override
    public boolean isAsrEnabled() {
        return ASR_ENABLED.get();
    }

    public void setAsrEnabled(boolean enabled) {
        ASR_ENABLED.set(enabled);
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
    public boolean isTtsEnabled() {
        return TTS_ENABLED.get();
    }

    public void setTtsEnabled(boolean enabled) {
        TTS_ENABLED.set(enabled);
    }

    @Override
    public boolean isTtsStreamingSynthesisEnabled() {
        return TTS_STREAMING_SYNTHESIS.get();
    }

    public void setTtsStreamingSynthesisEnabled(boolean enabled) {
        TTS_STREAMING_SYNTHESIS.set(enabled);
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

    @Override
    public String getLlmRemoteBaseUrl() {
        return llmModelSettings().activeModel().baseUrl();
    }

    @Override
    public void setLlmRemoteBaseUrl(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        LLM_REMOTE_BASE_URL.set(value);
        mirrorIntoActiveEntry(entry -> entry.withBaseUrl(orDefault(value, LlmConfiguration.DEFAULT_LLM_REMOTE_BASE_URL)));
    }

    @Override
    public String getLlmRemoteApiKey() {
        return getLlmApiKey(getLlmActiveModelId());
    }

    @Override
    public void setLlmRemoteApiKey(String apiKey) {
        String value = apiKey == null ? "" : apiKey.trim();
        LLM_REMOTE_API_KEY.set(value);
        mirrorCredentialIntoActiveEntry(value);
    }

    @Override
    public String getLlmRemoteModel() {
        return llmModelSettings().activeModel().modelId();
    }

    @Override
    public void setLlmRemoteModel(String model) {
        String value = model == null ? "" : model.trim();
        LLM_REMOTE_MODEL.set(value);
        mirrorIntoActiveEntry(entry -> entry.withModelId(orDefault(value, LlmConfiguration.DEFAULT_LLM_REMOTE_MODEL)));
    }

    @Override
    public int getLlmContextWindowTokens() {
        return llmModelSettings().activeModel().contextWindowTokens();
    }

    public void setLlmContextWindowTokens(int windowTokens) {
        int clamped = clampContextWindow(windowTokens);
        LLM_CONTEXT_WINDOW_TOKENS.set(clamped);
        mirrorIntoActiveEntry(entry -> entry.withContextWindowTokens(clamped));
    }

    @Override
    public List<LlmModelEntry> getLlmModels() {
        return llmModelSettings().models();
    }

    @Override
    public String getLlmActiveModelId() {
        return llmModelSettings().activeModelId();
    }

    @Override
    public String getLlmApiKey(String modelId) {
        return llmModelSettings().apiKey(modelId);
    }

    @Override
    public void setLlmModels(List<LlmModelEntry> models) {
        persistModelDocument(llmModelSettings().withModels(models));
    }

    @Override
    public void setLlmActiveModelId(String modelId) {
        persistModelDocument(llmModelSettings().withActiveModelId(modelId));
    }

    @Override
    public void setLlmApiKey(String modelId, String apiKey) {
        persistCredentials(llmModelSettings().withApiKey(modelId, apiKey));
    }

    /**
     * The one place the persisted document is assembled: the model list and the credential key, with
     * the legacy single-model keys as the entry that an older config file still resolves to.
     */
    private LlmModelSettings llmModelSettings() {
        String legacyApiKey = LLM_REMOTE_API_KEY.get();
        return LlmModelSettings.fromStored(
                LLM_MODELS.get(),
                LLM_ACTIVE_MODEL_ID.get(),
                LLM_CREDENTIALS.get(),
                LlmModelEntry.legacy(
                        orDefault(LLM_REMOTE_BASE_URL.get(), LlmConfiguration.DEFAULT_LLM_REMOTE_BASE_URL),
                        orDefault(LLM_REMOTE_MODEL.get(), LlmConfiguration.DEFAULT_LLM_REMOTE_MODEL),
                        clampContextWindow(LLM_CONTEXT_WINDOW_TOKENS.get())),
                legacyApiKey == null ? "" : legacyApiKey.trim());
    }

    /**
     * Writes the entry list, its active pointer and the credential store. The credential stays in its
     * own key, and the legacy credential is adopted into that store on the way out, so a materialised
     * model list keeps the key even if the old key is retired afterwards.
     */
    private void persistModelDocument(LlmModelSettings settings) {
        LlmModelSettings document = settings.withLegacyCredentialAdopted();
        LLM_MODELS.set(document.modelLines());
        LLM_ACTIVE_MODEL_ID.set(document.activeModelId());
        LLM_CREDENTIALS.set(document.credentialLines());
    }

    private void persistCredentials(LlmModelSettings settings) {
        LLM_CREDENTIALS.set(settings.withLegacyCredentialAdopted().credentialLines());
    }

    /**
     * Keeps the active entry in step with a write through the single-model setters, so a page that
     * still edits them cannot silently disagree with the list. A profile without a list is left alone:
     * its legacy keys are the only store and stay that way until something writes a list.
     */
    private void mirrorIntoActiveEntry(UnaryOperator<LlmModelEntry> update) {
        LlmModelSettings settings = llmModelSettings();
        if (!settings.storedModelsPresent()) {
            return;
        }
        persistModelDocument(settings.withEntry(update.apply(settings.activeModel())));
    }

    /** Same mirroring for the credential, which lives in its own key rather than in the entry. */
    private void mirrorCredentialIntoActiveEntry(String apiKey) {
        LlmModelSettings settings = llmModelSettings();
        if (!settings.storedModelsPresent()) {
            return;
        }
        persistCredentials(settings.withApiKey(settings.activeModelId(), apiKey));
    }

    @Override
    public int getLlmContextTier() {
        return LLM_CONTEXT_TIER.get();
    }

    public void setLlmContextTier(int tier) {
        LLM_CONTEXT_TIER.set(tier);
    }

    @Override
    public double getLlmUsageCacheMissInputPricePerMillion() {
        return LLM_USAGE_CACHE_MISS_INPUT_PRICE_PER_MILLION.get();
    }

    public void setLlmUsageCacheMissInputPricePerMillion(double price) {
        LLM_USAGE_CACHE_MISS_INPUT_PRICE_PER_MILLION.set(clampPrice(price));
    }

    @Override
    public double getLlmUsageCacheHitInputPricePerMillion() {
        return LLM_USAGE_CACHE_HIT_INPUT_PRICE_PER_MILLION.get();
    }

    public void setLlmUsageCacheHitInputPricePerMillion(double price) {
        LLM_USAGE_CACHE_HIT_INPUT_PRICE_PER_MILLION.set(clampPrice(price));
    }

    @Override
    public double getLlmUsageOutputPricePerMillion() {
        return LLM_USAGE_OUTPUT_PRICE_PER_MILLION.get();
    }

    public void setLlmUsageOutputPricePerMillion(double price) {
        LLM_USAGE_OUTPUT_PRICE_PER_MILLION.set(clampPrice(price));
    }

    @Override
    public boolean assistantEnabled() {
        return AX_ENABLED.get();
    }

    public void setAxEnabled(boolean enabled) {
        AX_ENABLED.set(enabled);
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
    public boolean allowInterruption() {
        return AX_ALLOW_INTERRUPTION.get();
    }

    public void setAxAllowInterruption(boolean enabled) {
        AX_ALLOW_INTERRUPTION.set(enabled);
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

    @Override
    public boolean isPresenceIconEnabled() {
        return PRESENCE_ICON_ENABLED.get();
    }

    @Override
    public void setPresenceIconEnabled(boolean enabled) {
        PRESENCE_ICON_ENABLED.set(enabled);
    }

    @Override
    public double getPresenceIconSize() {
        return clampPosition(PRESENCE_ICON_SIZE_PIXELS.get(), PresenceHudSettings.DEFAULT_ICON_SIZE_PIXELS,
                PresenceHudSettings.MIN_ICON_SIZE_PIXELS, PresenceHudSettings.MAX_ICON_SIZE_PIXELS);
    }

    @Override
    public void setPresenceIconSize(double size) {
        PRESENCE_ICON_SIZE_PIXELS.set(clampPosition(size, PresenceHudSettings.DEFAULT_ICON_SIZE_PIXELS,
                PresenceHudSettings.MIN_ICON_SIZE_PIXELS, PresenceHudSettings.MAX_ICON_SIZE_PIXELS));
    }

    @Override
    public PresenceHudVisualPreset getPresenceVisualPreset() {
        PresenceHudVisualPreset preset = PRESENCE_VISUAL_PRESET.get();
        return preset == null ? PresenceHudVisualPreset.PRESET_ONE : preset;
    }

    @Override
    public void setPresenceVisualPreset(PresenceHudVisualPreset preset) {
        PRESENCE_VISUAL_PRESET.set(preset == null ? PresenceHudVisualPreset.PRESET_ONE : preset);
    }

    @Override
    public double getPresenceIconPositionX() {
        return clampPosition(PRESENCE_ICON_POSITION_X.get(), 0.5D);
    }

    @Override
    public void setPresenceIconPositionX(double position) {
        PRESENCE_ICON_POSITION_X.set(clampPosition(position, 0.5D));
    }

    @Override
    public double getPresenceIconPositionY() {
        return clampPosition(PRESENCE_ICON_POSITION_Y.get(), -1.0D, -1.0D, 1.0D);
    }

    @Override
    public void setPresenceIconPositionY(double position) {
        PRESENCE_ICON_POSITION_Y.set(clampPosition(position, -1.0D, -1.0D, 1.0D));
    }

    @Override
    public boolean isDebugEnabled() {
        return TianshuBuildProfile.debugBuild() && DEBUG_ENABLED.get();
    }

    @Override
    public void setDebugEnabled(boolean enabled) {
        DEBUG_ENABLED.set(enabled);
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
        return gameConfigDirectory;
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
        com.rheinmetal.tianshu.model.LlmModelInfo embedding = com.rheinmetal.tianshu.model.LlmModelManager.getDefaultEmbeddingModel(languageCodeSupplier.get());
        return embedding != null ? embedding.name : "";
    }

    @Override
    public int getLlmEmbeddingGpuLayers() {
        return LLM_EMBEDDING_GPU_LAYERS.get();
    }

    /**
     * A hand-edited file cannot push the window below the minimum, and the model's own ceiling is
     * applied by the engine provider on top of this value.
     */
    @Override
    public int getLlmEmbeddingContextSize() {
        return Math.max(LlmConfiguration.MIN_EMBEDDING_CONTEXT_SIZE,
                Math.min(LlmConfiguration.MAX_EMBEDDING_CONTEXT_SIZE, LLM_EMBEDDING_CONTEXT_SIZE.get()));
    }

    /** The position is a two-value choice; a hand-edited file cannot push it outside that range. */
    @Override
    public void setLlmEmbeddingGpuLayers(int layers) {
        LLM_EMBEDDING_GPU_LAYERS.set(Math.max(0, Math.min(LlmConfiguration.EMBEDDING_GPU_LAYERS_ALL, layers)));
    }

    public void save() {
        SPEC.save();
    }

    private static double clampPosition(double value, double fallback) {
        return clampPosition(value, fallback, 0.0D, 1.0D);
    }

    /** A blank config entry would otherwise disable the remote lane, so it falls back to the default. */
    private static String orDefault(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static double clampPrice(double value) {
        return clampPosition(value, LlmConfiguration.MIN_LLM_USAGE_PRICE_PER_MILLION,
                LlmConfiguration.MIN_LLM_USAGE_PRICE_PER_MILLION,
                LlmConfiguration.MAX_LLM_USAGE_PRICE_PER_MILLION);
    }

    /** Keeps a declared window inside the accepted range before it reaches an entry or the file. */
    private static int clampContextWindow(int windowTokens) {
        return Math.max(LlmContextTiers.minimumTier(),
                Math.min(LlmConfiguration.MAX_LLM_CONTEXT_WINDOW_TOKENS, windowTokens));
    }

    private static double clampPosition(double value, double fallback, double min, double max) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
