package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import com.rheinmetal.tianshu.function.llm.runtime.LlmContextBudgetSnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmEngineCapabilitySnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationRequest;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCapabilitySnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpTrialSnapshot;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.InferenceLane;
import com.rheinmetal.tianshu.libs.llm.InferenceOptions;
import com.rheinmetal.tianshu.libs.llm.LlmContextBudgetPlan;
import com.rheinmetal.tianshu.libs.llm.LlmGenerationResult;
import com.rheinmetal.tianshu.libs.llm.LlmRuntimeCapabilities;
import com.rheinmetal.tianshu.libs.llm.LlmStreamFinish;
import com.rheinmetal.tianshu.libs.llm.MtpCapability;
import com.rheinmetal.tianshu.libs.llm.MtpTrialResult;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public final class JavaLlamaInferenceClient implements LlmInferenceClient {
    private final JavaLlamaServer server;

    public JavaLlamaInferenceClient(JavaLlamaServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) throws Exception {
        return await(server.chat(messages, sampler, maxTokens));
    }

    @Override
    public String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options) throws Exception {
        InferenceOptions libsOptions = toLibsOptions(options);
        CompletableFuture<String> future = libsOptions == null
                ? server.chat(messages, sampler, maxTokens)
                : server.chat(messages, sampler, maxTokens, libsOptions);
        return await(future);
    }

    @Override
    public LlmGenerationResult chatWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options) throws Exception {
        InferenceOptions libsOptions = toLibsOptions(options);
        CompletableFuture<LlmGenerationResult> future = libsOptions == null
                ? server.chatWithUsage(messages, sampler, maxTokens)
                : server.chatWithUsage(messages, sampler, maxTokens, libsOptions);
        return await(future);
    }

    @Override
    public void chatStream(List<ChatMessage> messages, SamplerConfig sampler, Consumer<String> onToken) throws Exception {
        await(server.chatStream(messages, sampler, onToken));
    }

    @Override
    public void chatStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options, Consumer<String> onToken) throws Exception {
        InferenceOptions libsOptions = toLibsOptions(options);
        CompletableFuture<String> future;
        if (libsOptions == null) {
            future = server.chatStream(messages, sampler, maxTokens, onToken);
        } else {
            future = server.chatStream(messages, sampler, maxTokens, libsOptions, onToken);
        }
        await(future);
    }

    @Override
    public CompletableFuture<String> chatStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options, Consumer<String> onToken, Consumer<LlmStreamFinish> onFinish) {
        InferenceOptions libsOptions = toLibsOptions(options);
        return libsOptions == null
                ? server.chatStream(messages, sampler, maxTokens, InferenceOptions.defaults(), onToken, onFinish)
                : server.chatStream(messages, sampler, maxTokens, libsOptions, onToken, onFinish);
    }

    @Override
    public CompletableFuture<String> chatStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options, Consumer<String> onToken, Consumer<String> onThinking, Consumer<LlmStreamFinish> onFinish) {
        InferenceOptions libsOptions = toLibsOptions(options);
        return server.chatStream(
                messages,
                sampler,
                maxTokens,
                libsOptions == null ? InferenceOptions.defaults() : libsOptions,
                onToken,
                onThinking,
                onFinish
        );
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
    public CompletableFuture<LlmGenerationResult> taskWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options) {
        InferenceOptions libsOptions = toLibsOptions(options);
        return server.taskWithUsage(messages, sampler, maxTokens, priority, preemptible,
                libsOptions == null ? InferenceOptions.defaults() : libsOptions);
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
    public CompletableFuture<LlmGenerationResult> taskStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options, Consumer<String> onToken, Consumer<LlmStreamFinish> onFinish) {
        InferenceOptions libsOptions = toLibsOptions(options);
        return server.taskStreamWithUsage(messages, sampler, maxTokens, priority, preemptible,
                libsOptions == null ? InferenceOptions.defaults() : libsOptions, onToken, onFinish);
    }

    @Override
    public CompletableFuture<LlmGenerationResult> taskStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options, Consumer<String> onToken, Consumer<String> onThinking, Consumer<LlmStreamFinish> onFinish) {
        InferenceOptions libsOptions = toLibsOptions(options);
        return server.taskStreamWithUsage(
                messages,
                sampler,
                maxTokens,
                priority,
                preemptible,
                libsOptions == null ? InferenceOptions.defaults() : libsOptions,
                onToken,
                onThinking,
                onFinish
        );
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
    public int countChatPromptTokens(List<ChatMessage> messages, SamplerConfig sampler) {
        return server.countChatPromptTokens(messages, sampler);
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
    public boolean hasQueueCapacity() {
        return server.hasQueueCapacity();
    }

    @Override
    public int getChatQueueSize() {
        return server.getChatQueueSize();
    }

    @Override
    public int getTaskQueueSize() {
        return 0;
    }

    @Override
    public int getQueueSize() {
        return server.getChatQueueSize();
    }

    @Override
    public boolean supportsThinking() {
        return toSnapshot(server.getRuntimeCapabilities()).supportsThinking();
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
    public LlmEngineCapabilitySnapshot getRuntimeCapabilities() {
        return toSnapshot(server.getRuntimeCapabilities());
    }

    @Override
    public LlmContextBudgetSnapshot getContextBudgetPlan() {
        return toSnapshot(server.getContextBudgetPlan());
    }

    @Override
    public LlmContextBudgetSnapshot getContextBudgetPlan(String lane) {
        return toSnapshot(server.getContextBudgetPlan(InferenceLane.parse(lane)));
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
                .captureThinkingContent(options.captureThinkingContent())
                .toolsJson(options.toolsJson())
                .build();
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        try {
            return future.get();
        } catch (CancellationException e) {
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CancellationException cancellation) {
                throw cancellation;
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
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

    private static LlmEngineCapabilitySnapshot toSnapshot(LlmRuntimeCapabilities capabilities) {
        if (capabilities == null) {
            return LlmEngineCapabilitySnapshot.unavailable();
        }
        return new LlmEngineCapabilitySnapshot(
                capabilities.ready(),
                capabilities.supportsThinking(),
                capabilities.supportsMtp(),
                capabilities.supportsEmbeddedMtp(),
                capabilities.externalMtpAvailable(),
                capabilities.mtpLayerCount()
        );
    }

    private static LlmContextBudgetSnapshot toSnapshot(LlmContextBudgetPlan plan) {
        if (plan == null) {
            return LlmContextBudgetSnapshot.unavailable("LLM context budget plan is unavailable");
        }
        return new LlmContextBudgetSnapshot(
                plan.requestedContextSize(),
                plan.trainingContextSize(),
                plan.memoryContextSize(),
                plan.plannedContextSize(),
                plan.promptTokenBudget(),
                plan.promptMarginTokens(),
                plan.safetyMarginBytes(),
                plan.reliable(),
                plan.limitation()
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
