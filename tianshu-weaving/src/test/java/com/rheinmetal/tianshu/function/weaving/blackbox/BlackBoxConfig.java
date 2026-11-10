package com.rheinmetal.tianshu.function.weaving.blackbox;

import com.rheinmetal.tianshu.function.weaving.settings.LlmReasoningProfile;
import com.rheinmetal.tianshu.function.weaving.settings.LlmWireCompat;
import com.rheinmetal.tianshu.function.weaving.settings.WeavingConfiguration;

import java.nio.file.Path;

/**
 * The configuration of a black-box run: everything the module reads, pointed at a local endpoint and
 * a throwaway directory. No settings storage is involved, so a test states exactly the profile it
 * wants instead of wrestling with a page.
 */
final class BlackBoxConfig implements WeavingConfiguration {
    private final Path root;
    private final String baseUrl;
    private String apiKey = "sk-black-box";
    private String model = "test-model";
    private int contextWindowTokens = 200_000;
    private int requestTimeoutSeconds = 10;
    private long autoLoadDelayMillis = 0L;
    private String embeddingModelName = "";
    private LlmReasoningProfile reasoning = LlmReasoningProfile.NONE;
    private LlmWireCompat wireCompat = LlmWireCompat.DEFAULT;

    BlackBoxConfig(Path root, String baseUrl) {
        this.root = root;
        this.baseUrl = baseUrl;
    }

    BlackBoxConfig apiKey(String apiKey) {
        this.apiKey = apiKey;
        return this;
    }

    BlackBoxConfig model(String model) {
        this.model = model;
        return this;
    }

    BlackBoxConfig contextWindowTokens(int tokens) {
        this.contextWindowTokens = tokens;
        return this;
    }

    BlackBoxConfig requestTimeoutSeconds(int seconds) {
        this.requestTimeoutSeconds = seconds;
        return this;
    }

    BlackBoxConfig reasoning(LlmReasoningProfile reasoning) {
        this.reasoning = reasoning == null ? LlmReasoningProfile.NONE : reasoning;
        return this;
    }

    BlackBoxConfig embeddingModelName(String embeddingModelName) {
        this.embeddingModelName = embeddingModelName == null ? "" : embeddingModelName;
        return this;
    }

    BlackBoxConfig wireCompat(LlmWireCompat wireCompat) {
        this.wireCompat = wireCompat == null ? LlmWireCompat.DEFAULT : wireCompat;
        return this;
    }

    @Override
    public boolean isLlmEnabled() {
        return true;
    }

    @Override
    public String getCustomLlmName() {
        return "black-box";
    }

    @Override
    public Path getLlmBasePath() {
        return root.resolve("llm");
    }

    @Override
    public String getLlmRemoteBaseUrl() {
        return baseUrl;
    }

    @Override
    public String getLlmRemoteApiKey() {
        return apiKey;
    }

    @Override
    public String getLlmRemoteModel() {
        return model;
    }

    @Override
    public String getLlmProviderId() {
        return "custom";
    }

    @Override
    public int getLlmContextWindowTokens() {
        return contextWindowTokens;
    }

    @Override
    public int getLlmRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    @Override
    public long getLlmAutoLoadDelayMillis() {
        return autoLoadDelayMillis;
    }

    @Override
    public LlmReasoningProfile getLlmReasoningProfile() {
        return reasoning;
    }

    @Override
    public LlmWireCompat getLlmWireCompat() {
        return wireCompat;
    }

    @Override
    public int getLlmEmbeddingGpuLayers() {
        return 0;
    }

    @Override
    public String getLlmEmbeddingModelName() {
        return embeddingModelName;
    }
}