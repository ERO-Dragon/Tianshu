package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationRequest;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCapabilitySnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpTrialSnapshot;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.InferenceOptions;
import com.rheinmetal.tianshu.libs.llm.MtpCapability;
import com.rheinmetal.tianshu.libs.llm.MtpTrialResult;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class JavaLlamaInferenceClient implements LlmInferenceClient {
    private final JavaLlamaServer server;

    public JavaLlamaInferenceClient(JavaLlamaServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) throws Exception {
        return server.chat(messages, sampler, maxTokens);
    }

    @Override
    public String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options) throws Exception {
        InferenceOptions libsOptions = toLibsOptions(options);
        return libsOptions == null
                ? server.chat(messages, sampler, maxTokens)
                : server.chat(messages, sampler, maxTokens, libsOptions);
    }

    @Override
    public void chatStream(List<ChatMessage> messages, SamplerConfig sampler, Consumer<String> onToken) throws Exception {
        server.chatStream(messages, sampler, onToken);
    }

    @Override
    public void chatStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options, Consumer<String> onToken) throws Exception {
        InferenceOptions libsOptions = toLibsOptions(options);
        if (libsOptions == null) {
            server.chatStream(messages, sampler, maxTokens, onToken);
        } else {
            server.chatStream(messages, sampler, maxTokens, libsOptions, onToken);
        }
    }

    @Override
    public CompletableFuture<String> task(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible) {
        return server.task(messages, sampler, maxTokens, priority, preemptible);
    }

    @Override
    public CompletableFuture<String> task(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options) {
        InferenceOptions libsOptions = toLibsOptions(options);
        return libsOptions == null
                ? server.task(messages, sampler, maxTokens, priority, preemptible)
                : server.task(messages, sampler, maxTokens, priority, preemptible, libsOptions);
    }

    @Override
    public CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, Consumer<String> onToken) {
        return server.taskStream(messages, sampler, maxTokens, priority, preemptible, onToken);
    }

    @Override
    public CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options, Consumer<String> onToken) {
        InferenceOptions libsOptions = toLibsOptions(options);
        return libsOptions == null
                ? server.taskStream(messages, sampler, maxTokens, priority, preemptible, onToken)
                : server.taskStream(messages, sampler, maxTokens, priority, preemptible, libsOptions, onToken);
    }

    @Override
    public float[] embed(String text) throws Exception {
        return server.embed(text);
    }

    @Override
    public float[][] embed(List<String> texts) throws Exception {
        return server.embed(texts);
    }

    @Override
    public List<RagSearchResult> search(String queryText, List<String> texts, int topK, float threshold) {
        return server.search(queryText, texts, topK, threshold);
    }

    @Override
    public boolean isReady() {
        return server.isReady();
    }

    @Override
    public boolean hasChatQueueCapacity() {
        return server.hasChatQueueCapacity();
    }

    @Override
    public boolean hasTaskQueueCapacity() {
        return server.hasTaskQueueCapacity();
    }

    @Override
    public boolean supportsEnableThinking() {
        return server.supportsEnableThinking();
    }

    @Override
    public boolean supportsMtp() {
        return server.supportsMtp();
    }

    @Override
    public LlmMtpCapabilitySnapshot getMtpCapability() {
        return toSnapshot(server.getMtpCapability());
    }

    @Override
    public CompletableFuture<LlmMtpCalibrationResult> calibrateMtpAsync(LlmMtpCalibrationRequest request) {
        return server.calibrateMtpAsync(toLibsCalibrationRequest(request)).thenApply(JavaLlamaInferenceClient::toSnapshot);
    }

    private static InferenceOptions toLibsOptions(LlmInferenceOptions options) {
        if (options == null || !options.hasExecutionOptions()) {
            return null;
        }
        return InferenceOptions.builder()
                .mtpEnabled(options.mtpEnabled())
                .mtpDraftMax(options.mtpDraftMax())
                .vulkanPriority(options.vulkanPriority())
                .build();
    }

    private static com.rheinmetal.tianshu.libs.llm.MtpCalibrationRequest toLibsCalibrationRequest(LlmMtpCalibrationRequest request) {
        com.rheinmetal.tianshu.libs.llm.MtpCalibrationRequest defaults = com.rheinmetal.tianshu.libs.llm.MtpCalibrationRequest.defaults();
        if (request == null) {
            return defaults;
        }
        int maxDraftMax = request.maxDraftMax() != null ? request.maxDraftMax() : defaults.getMaxDraftMax();
        int maxTokens = request.maxTokens() != null ? request.maxTokens() : defaults.getMaxTokens();
        int targetPromptTokens = request.targetPromptTokens() != null ? request.targetPromptTokens() : defaults.getTargetPromptTokens();
        return new com.rheinmetal.tianshu.libs.llm.MtpCalibrationRequest(maxDraftMax, maxTokens, targetPromptTokens);
    }

    private static LlmMtpCapabilitySnapshot toSnapshot(MtpCapability capability) {
        if (capability == null || !capability.isSupported()) {
            return LlmMtpCapabilitySnapshot.unsupported();
        }
        return new LlmMtpCapabilitySnapshot(
                capability.isSupported(),
                capability.getMtpLayerCount(),
                capability.isCalibrated(),
                capability.getRecommendedDraftMax(),
                toSnapshot(capability.getBestTrial())
        );
    }

    private static LlmMtpCalibrationResult toSnapshot(com.rheinmetal.tianshu.libs.llm.MtpCalibrationResult result) {
        if (result == null || !result.isSupported()) {
            return LlmMtpCalibrationResult.unsupported();
        }
        return new LlmMtpCalibrationResult(
                result.isSupported(),
                result.getMtpLayerCount(),
                result.getMaxDraftMaxTested(),
                result.getBestDraftMax(),
                result.getMessage(),
                result.getTrials().stream().map(JavaLlamaInferenceClient::toSnapshot).toList()
        );
    }

    private static LlmMtpTrialSnapshot toSnapshot(MtpTrialResult trial) {
        if (trial == null) {
            return null;
        }
        return new LlmMtpTrialSnapshot(
                trial.getDraftMax(),
                trial.isSuccess(),
                trial.getErrorMessage(),
                trial.getPromptTokens(),
                trial.getGeneratedTokens(),
                trial.getDraftedTokens(),
                trial.getAcceptedDraftTokens(),
                trial.getAcceptanceRate(),
                trial.getTokensPerSecond()
        );
    }
}
