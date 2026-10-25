package com.rheinmetal.tianshu.function.weaving.settings;

import com.rheinmetal.tianshu.api.config.ModuleConfigStore;
import com.rheinmetal.tianshu.function.weaving.model.LlmModelInfo;
import com.rheinmetal.tianshu.function.weaving.model.LlmModelManager;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Weaving's own settings.
 *
 * <p>The model list is structured data, so it is kept in the same line format it always used — one
 * entry per line, one model per line — and encoded by {@link LlmModelSettings}, which lives in this
 * module. Credentials stay in a separate key and are never serialised together with the list.</p>
 */
public final class WeavingConfig implements WeavingConfiguration, WeavingSettingsAccess {

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_CUSTOM_NAME = "customName";
    private static final String KEY_LEGACY_BASE_URL = "legacyBaseUrl";
    private static final String KEY_LEGACY_API_KEY = "legacyApiKey";
    private static final String KEY_LEGACY_MODEL = "legacyModel";
    private static final String KEY_CONTEXT_WINDOW = "contextWindowTokens";
    private static final String KEY_CONTEXT_TIER = "contextTier";
    private static final String KEY_MODELS = "models";
    private static final String KEY_ACTIVE_MODEL_ID = "activeModelId";
    private static final String KEY_CREDENTIALS = "credentials";
    private static final String KEY_EMBEDDING_GPU_LAYERS = "embeddingGpuLayers";
    private static final String KEY_EMBEDDING_CONTEXT_SIZE = "embeddingContextSize";
    private static final String KEY_PRICE_CACHE_MISS = "usageCacheMissInputPricePerMillion";
    private static final String KEY_PRICE_CACHE_HIT = "usageCacheHitInputPricePerMillion";
    private static final String KEY_PRICE_OUTPUT = "usageOutputPricePerMillion";

    private static final boolean DEFAULT_ENABLED = true;
    private static final String DEFAULT_CUSTOM_NAME = "";
    private static final int DEFAULT_CONTEXT_WINDOW = WeavingConfiguration.DEFAULT_LLM_CONTEXT_WINDOW_TOKENS;
    private static final int DEFAULT_EMBEDDING_GPU_LAYERS = WeavingConfiguration.EMBEDDING_GPU_LAYERS_ALL;
    private static final int DEFAULT_EMBEDDING_CONTEXT_SIZE = WeavingConfiguration.DEFAULT_EMBEDDING_CONTEXT_SIZE;
    private static final double DEFAULT_PRICE_CACHE_MISS =
            WeavingConfiguration.DEFAULT_LLM_USAGE_CACHE_MISS_INPUT_PRICE_PER_MILLION;
    private static final double DEFAULT_PRICE_CACHE_HIT =
            WeavingConfiguration.DEFAULT_LLM_USAGE_CACHE_HIT_INPUT_PRICE_PER_MILLION;
    private static final double DEFAULT_PRICE_OUTPUT =
            WeavingConfiguration.DEFAULT_LLM_USAGE_OUTPUT_PRICE_PER_MILLION;

    private final ModuleConfigStore store;
    private final Path basePath;
    private final Supplier<String> languageCode;

    public WeavingConfig(ModuleConfigStore store, Path moduleDirectory, Supplier<String> languageCode) {
        this.store = Objects.requireNonNull(store, "store");
        this.basePath = Objects.requireNonNull(moduleDirectory, "moduleDirectory")
                .toAbsolutePath()
                .normalize()
                .resolve("llm");
        this.languageCode = languageCode == null ? () -> "" : languageCode;
    }

    @Override
    public boolean isLlmEnabled() {
        return store.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
    }

    @Override
    public void setLlmEnabled(boolean enabled) {
        store.setBoolean(KEY_ENABLED, enabled);
    }

    @Override
    public String getCustomLlmName() {
        return store.getString(KEY_CUSTOM_NAME, DEFAULT_CUSTOM_NAME);
    }

    @Override
    public void setCustomLlmName(String name) {
        store.setString(KEY_CUSTOM_NAME, name == null ? "" : name);
    }

    @Override
    public Path getLlmBasePath() {
        return basePath;
    }

    @Override
    public String getLlmRemoteBaseUrl() {
        return modelSettings().activeModel().baseUrl();
    }

    @Override
    public void setLlmRemoteBaseUrl(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        store.setString(KEY_LEGACY_BASE_URL, value);
        mirrorIntoActiveEntry(entry -> entry.withBaseUrl(
                orDefault(value, WeavingConfiguration.DEFAULT_LLM_REMOTE_BASE_URL)));
    }

    @Override
    public String getLlmRemoteApiKey() {
        return getLlmApiKey(getLlmActiveModelId());
    }

    @Override
    public void setLlmRemoteApiKey(String apiKey) {
        String value = apiKey == null ? "" : apiKey.trim();
        store.setString(KEY_LEGACY_API_KEY, value);
        mirrorCredentialIntoActiveEntry(value);
    }

    @Override
    public String getLlmRemoteModel() {
        return modelSettings().activeModel().modelId();
    }

    @Override
    public void setLlmRemoteModel(String model) {
        String value = model == null ? "" : model.trim();
        store.setString(KEY_LEGACY_MODEL, value);
        mirrorIntoActiveEntry(entry -> entry.withModelId(
                orDefault(value, WeavingConfiguration.DEFAULT_LLM_REMOTE_MODEL)));
    }

    @Override
    public int getLlmContextWindowTokens() {
        return modelSettings().activeModel().contextWindowTokens();
    }

    public void setLlmContextWindowTokens(int windowTokens) {
        int clamped = clampContextWindow(windowTokens);
        store.setInt(KEY_CONTEXT_WINDOW, clamped);
        mirrorIntoActiveEntry(entry -> entry.withContextWindowTokens(clamped));
    }

    @Override
    public List<WeavingModelEntry> getLlmModels() {
        return modelSettings().models();
    }

    @Override
    public String getLlmActiveModelId() {
        return modelSettings().activeModelId();
    }

    @Override
    public String getLlmApiKey(String modelId) {
        return modelSettings().apiKey(modelId);
    }

    @Override
    public void setLlmModels(List<WeavingModelEntry> models) {
        persistModelDocument(modelSettings().withModels(models));
    }

    @Override
    public void setLlmActiveModelId(String modelId) {
        persistModelDocument(modelSettings().withActiveModelId(modelId));
    }

    @Override
    public void setLlmApiKey(String modelId, String apiKey) {
        persistCredentials(modelSettings().withApiKey(modelId, apiKey));
    }

    @Override
    public String getLlmEmbeddingModelName() {
        LlmModelInfo embedding = LlmModelManager.getDefaultEmbeddingModel(languageCode.get());
        return embedding == null ? "" : embedding.name;
    }

    @Override
    public int getLlmEmbeddingGpuLayers() {
        return store.getInt(KEY_EMBEDDING_GPU_LAYERS, DEFAULT_EMBEDDING_GPU_LAYERS);
    }

    @Override
    public void setLlmEmbeddingGpuLayers(int layers) {
        store.setInt(KEY_EMBEDDING_GPU_LAYERS, Math.max(0, layers));
    }

    @Override
    public int getLlmEmbeddingContextSize() {
        return clampEmbeddingContextSize(store.getInt(KEY_EMBEDDING_CONTEXT_SIZE, DEFAULT_EMBEDDING_CONTEXT_SIZE));
    }

    @Override
    public double getLlmUsageCacheMissInputPricePerMillion() {
        return store.getDouble(KEY_PRICE_CACHE_MISS, DEFAULT_PRICE_CACHE_MISS);
    }

    public void setLlmUsageCacheMissInputPricePerMillion(double price) {
        store.setDouble(KEY_PRICE_CACHE_MISS, clampPrice(price));
    }

    @Override
    public double getLlmUsageCacheHitInputPricePerMillion() {
        return store.getDouble(KEY_PRICE_CACHE_HIT, DEFAULT_PRICE_CACHE_HIT);
    }

    public void setLlmUsageCacheHitInputPricePerMillion(double price) {
        store.setDouble(KEY_PRICE_CACHE_HIT, clampPrice(price));
    }

    @Override
    public double getLlmUsageOutputPricePerMillion() {
        return store.getDouble(KEY_PRICE_OUTPUT, DEFAULT_PRICE_OUTPUT);
    }

    public void setLlmUsageOutputPricePerMillion(double price) {
        store.setDouble(KEY_PRICE_OUTPUT, clampPrice(price));
    }

    @Override
    public void save() {
        store.save();
    }

    private int getContextTier() {
        return store.getInt(KEY_CONTEXT_TIER, WeavingContextTiers.DEFAULT_TIER);
    }

    public void setLlmContextTier(int tier) {
        store.setInt(KEY_CONTEXT_TIER, tier);
    }

    /**
     * The one place the persisted document is assembled: the model list and the credential key, with
     * the legacy single-model keys as the entry that an older profile still resolves to.
     */
    private LlmModelSettings modelSettings() {
        String legacyApiKey = store.getString(KEY_LEGACY_API_KEY, "");
        return LlmModelSettings.fromStored(
                store.getStringList(KEY_MODELS, List.of()),
                store.getString(KEY_ACTIVE_MODEL_ID, ""),
                store.getStringList(KEY_CREDENTIALS, List.of()),
                WeavingModelEntry.legacy(
                        orDefault(store.getString(KEY_LEGACY_BASE_URL, ""),
                                WeavingConfiguration.DEFAULT_LLM_REMOTE_BASE_URL),
                        orDefault(store.getString(KEY_LEGACY_MODEL, ""),
                                WeavingConfiguration.DEFAULT_LLM_REMOTE_MODEL),
                        clampContextWindow(store.getInt(KEY_CONTEXT_WINDOW, DEFAULT_CONTEXT_WINDOW))),
                legacyApiKey == null ? "" : legacyApiKey.trim());
    }

    /**
     * Writes the entry list, its active pointer and the credential store. The credential stays in its
     * own key, and the legacy credential is adopted into that store on the way out, so a materialised
     * model list keeps the key even if the old key is retired afterwards.
     */
    private void persistModelDocument(LlmModelSettings settings) {
        LlmModelSettings document = settings.withLegacyCredentialAdopted();
        store.setStringList(KEY_MODELS, document.modelLines());
        store.setString(KEY_ACTIVE_MODEL_ID, document.activeModelId());
        store.setStringList(KEY_CREDENTIALS, document.credentialLines());
    }

    private void persistCredentials(LlmModelSettings settings) {
        store.setStringList(KEY_CREDENTIALS, settings.withLegacyCredentialAdopted().credentialLines());
    }

    /**
     * Keeps the active entry in step with a write through the single-model setters, so a page that
     * still edits them cannot silently disagree with the list. A profile without a list is left alone:
     * its legacy keys are the only store and stay that way until something writes a list.
     */
    private void mirrorIntoActiveEntry(UnaryOperator<WeavingModelEntry> update) {
        LlmModelSettings settings = modelSettings();
        if (!settings.storedModelsPresent()) {
            return;
        }
        persistModelDocument(settings.withEntry(update.apply(settings.activeModel())));
    }

    /** Same mirroring for the credential, which lives in its own key rather than in the entry. */
    private void mirrorCredentialIntoActiveEntry(String apiKey) {
        LlmModelSettings settings = modelSettings();
        if (!settings.storedModelsPresent()) {
            return;
        }
        persistCredentials(settings.withApiKey(settings.activeModelId(), apiKey));
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int clampContextWindow(int windowTokens) {
        if (windowTokens <= 0) {
            return DEFAULT_CONTEXT_WINDOW;
        }
        return Math.min(windowTokens, WeavingConfiguration.MAX_LLM_CONTEXT_WINDOW_TOKENS);
    }

    private static int clampEmbeddingContextSize(int size) {
        return Math.max(WeavingConfiguration.MIN_EMBEDDING_CONTEXT_SIZE,
                Math.min(size, WeavingConfiguration.MAX_EMBEDDING_CONTEXT_SIZE));
    }

    private static double clampPrice(double price) {
        if (Double.isNaN(price)) {
            return WeavingConfiguration.MIN_LLM_USAGE_PRICE_PER_MILLION;
        }
        return Math.max(WeavingConfiguration.MIN_LLM_USAGE_PRICE_PER_MILLION,
                Math.min(price, WeavingConfiguration.MAX_LLM_USAGE_PRICE_PER_MILLION));
    }
}
