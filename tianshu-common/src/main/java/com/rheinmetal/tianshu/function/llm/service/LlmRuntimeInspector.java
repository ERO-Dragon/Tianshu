package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.function.llm.runtime.LlmContextBudgetSnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmEngineCapabilitySnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationRequest;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCapabilitySnapshot;
import com.rheinmetal.tianshu.protocol.payload.LLMRuntimeSnapshotPayload;

import java.util.List;
import java.util.concurrent.CompletableFuture;

final class LlmRuntimeInspector {
    private static final int DEFAULT_PROMPT_MARGIN_TOKENS = 64;

    private final LlmInferenceClient inferenceClient;
    private final EmbeddingService embeddingService;
    private final LlmServiceMetadata metadata;
    private final String cacheNamespace;
    private final boolean embeddingConfigured;

    LlmRuntimeInspector(
            LlmInferenceClient inferenceClient,
            EmbeddingService embeddingService,
            LlmServiceMetadata metadata,
            String cacheNamespace,
            boolean embeddingConfigured
    ) {
        this.inferenceClient = inferenceClient;
        this.embeddingService = embeddingService;
        this.metadata = metadata;
        this.cacheNamespace = clean(cacheNamespace);
        this.embeddingConfigured = embeddingConfigured;
    }

    boolean isReady() {
        return inferenceClient.isReady();
    }

    boolean hasChatQueueCapacity() {
        return inferenceClient.hasChatQueueCapacity();
    }

    boolean hasTaskQueueCapacity() {
        return inferenceClient.hasTaskQueueCapacity();
    }

    boolean supportsThinking() {
        return inferenceClient.supportsThinking();
    }

    boolean supportsMtp() {
        return inferenceClient.supportsMtp();
    }

    LlmMtpCapabilitySnapshot mtpCapability() {
        return inferenceClient.getMtpCapability();
    }

    LlmEngineCapabilitySnapshot runtimeCapabilities() {
        return inferenceClient.getRuntimeCapabilities();
    }

    LlmContextBudgetSnapshot contextBudgetPlan() {
        return inferenceClient.getContextBudgetPlan();
    }

    LlmContextBudgetSnapshot contextBudgetPlan(String lane) {
        return inferenceClient.getContextBudgetPlan(lane);
    }

    CompletableFuture<LlmMtpCalibrationResult> calibrateMtpAsync(LlmMtpCalibrationRequest request) {
        return inferenceClient.calibrateMtpAsync(request);
    }

    float[] embed(String text) throws Exception {
        return embeddingService.embed(text);
    }

    float[][] embed(List<String> texts) throws Exception {
        return embeddingService.embed(texts);
    }

    int embeddingDimension() {
        return Math.max(0, embeddingService.getEmbeddingDimension());
    }

    String embeddingModelName() {
        return metadata.embeddingModelName();
    }

    String embeddingNamespace() {
        if (!cacheNamespace.isBlank() && !"default".equals(cacheNamespace)) {
            return cacheNamespace;
        }
        return stableSegment(metadata.modelName()) + ":" + stableSegment(metadata.embeddingModelName());
    }

    LLMRuntimeSnapshotPayload snapshot(boolean includeRuntimeDetails) {
        int embeddingDimension = embeddingDimension();
        boolean embeddingAvailable = embeddingConfigured || embeddingDimension > 0;
        LlmMtpCapabilitySnapshot mtp = mtpCapability();
        LlmEngineCapabilitySnapshot capabilities = runtimeCapabilities();
        LlmContextBudgetSnapshot budget = contextBudgetPlan();
        int loadedContextSize = loadedContextSize(budget);
        return new LLMRuntimeSnapshotPayload(
                isReady(),
                inferenceClient.isReady(),
                embeddingAvailable,
                embeddingDimension,
                capabilities.supportsThinking(),
                supportsMtp(),
                capabilities.supportsEmbeddedMtp(),
                capabilities.externalMtpAvailable(),
                mtp != null && mtp.supported() && mtp.calibrated(),
                mtp == null ? 0 : mtp.mtpLayerCount(),
                mtp == null ? 0 : mtp.recommendedDraftMax(),
                loadedContextSize,
                contextTokenBudget(budget, loadedContextSize),
                hasChatQueueCapacity(),
                hasTaskQueueCapacity(),
                inferenceClient.hasQueueCapacity(),
                inferenceClient.getChatQueueSize(),
                inferenceClient.getTaskQueueSize(),
                inferenceClient.getQueueSize(),
                includeRuntimeDetails ? metadata.modelName() : "",
                "",
                includeRuntimeDetails ? metadata.embeddingModelName() : "",
                includeRuntimeDetails ? embeddingNamespace() : "",
                "",
                System.currentTimeMillis()
        );
    }

    private int loadedContextSize(LlmContextBudgetSnapshot budget) {
        int planned = budget == null ? 0 : budget.plannedContextSize();
        return planned > 0 ? planned : metadata.configuredContextSize();
    }

    private int contextTokenBudget(LlmContextBudgetSnapshot budget, int loadedContextSize) {
        int planned = budget == null ? 0 : budget.promptTokenBudget();
        if (planned > 0) {
            return planned;
        }
        int margin = budget == null ? DEFAULT_PROMPT_MARGIN_TOKENS : Math.max(0, budget.promptMarginTokens());
        return Math.max(0, loadedContextSize - margin);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stableSegment(String value) {
        String normalized = value == null || value.isBlank() ? "default" : value.trim();
        return Integer.toHexString(java.util.Objects.hash(normalized));
    }
}
