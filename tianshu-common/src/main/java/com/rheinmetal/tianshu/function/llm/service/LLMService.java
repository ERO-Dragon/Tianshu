package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.llm.runtime.LlmContextBudgetSnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmEngineCapabilitySnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationRequest;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCapabilitySnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmPerformanceProvider;
import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import com.rheinmetal.tianshu.libs.llm.LlmGenerationResult;
import com.rheinmetal.tianshu.libs.llm.LlmStreamFinish;
import com.rheinmetal.tianshu.libs.llm.LlmTokenUsage;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMRuntimeSnapshotPayload;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Stable public facade for LLM inference, RAG and runtime inspection. */
public class LLMService {
    private final IGameEnvironment env;
    private final LlmInferenceClient inferenceClient;
    private final LlmRagService ragService;
    private final LlmRequestPreparer requestPreparer;
    private final LlmRuntimeInspector runtimeInspector;
    private volatile boolean initialized;

    private LLMService(Builder builder) {
        this.env = Objects.requireNonNull(builder.env, "env");
        this.inferenceClient = Objects.requireNonNull(builder.inferenceClient, "inferenceClient");
        LlmServiceMetadata metadata = LlmServiceMetadata.from(builder.config);
        LlmInferenceGovernor governor = builder.inferenceGovernor != null
                ? builder.inferenceGovernor
                : new LlmInferenceGovernor(LlmInferenceDefaults.from(builder.config), builder.performanceProvider);
        EmbeddingService embeddingService = new LlmEmbeddingServiceAdapter(inferenceClient);
        this.ragService = builder.ragStorageService != null
                ? new LlmRagService(env, inferenceClient, builder.ragStorageService)
                : new LlmRagService(
                        env,
                        inferenceClient,
                        embeddingService,
                        builder.usePersistentCache,
                        builder.cacheDirectory,
                        builder.cacheNamespace,
                        builder.ragSearchExecutor,
                        builder.ragPersistenceScheduler
                );
        this.runtimeInspector = new LlmRuntimeInspector(
                inferenceClient,
                embeddingService,
                metadata,
                builder.cacheNamespace,
                builder.embeddingConfigured
        );
        this.requestPreparer = new LlmRequestPreparer(inferenceClient, governor, ragService, metadata);
        this.initialized = true;
        env.info("[LLMService] Initialized, cache mode: " + (builder.usePersistentCache ? "PERSISTENT" : "MEMORY"));
    }

    public static Builder builder() {
        return new Builder();
    }

    public String chat(String userMessage, String systemPrompt) {
        return chat(LLMRequest.of(
                Chunk.message(MessageItem.system(systemPrompt), MessageItem.user(userMessage))
        )).text();
    }

    public LLMResult chat(LLMRequest request) {
        LlmRequestPreparer.PreparedRequest prepared = requestPreparer.prepare(request);
        try {
            LlmGenerationResult result = inferenceClient.chatWithUsage(
                    prepared.messages(),
                    prepared.sampler(),
                    prepared.maxTokens(),
                    prepared.options()
            );
            LLMResult response = new LLMResult(
                    result == null ? "" : result.text(),
                    result == null ? "" : result.thinkingContent(),
                    prepared.ragHits(),
                    toUsagePayload(result == null ? null : result.usage())
            );
            publishDialogueDiagnostic("CHAT_COMPLETED", request, response.text());
            return response;
        } catch (Exception exception) {
            env.error("[LLMService] Chat failed", exception);
            throw new RuntimeException("LLM chat failed: " + safeMessage(exception), exception);
        }
    }

    public void chatStream(LLMRequest request, Consumer<String> onToken) {
        chatStream(request, onToken, null);
    }

    public LLMStreamResult chatStream(
            LLMRequest request,
            Consumer<String> onToken,
            List<LLMPromptResultPayload.RagHitPayload> ragHitsSink
    ) {
        return chatStream(request, onToken, null, ragHitsSink);
    }

    public LLMStreamResult chatStream(
            LLMRequest request,
            Consumer<String> onToken,
            Consumer<String> onThinking,
            List<LLMPromptResultPayload.RagHitPayload> ragHitsSink
    ) {
        LlmRequestPreparer.PreparedRequest prepared = requestPreparer.prepare(request);
        copyRagHits(prepared, ragHitsSink);
        try {
            LlmStreamFinishHolder finishHolder = new LlmStreamFinishHolder();
            CompletableFuture<String> future = inferenceClient.chatStreamWithUsage(
                    prepared.messages(),
                    prepared.sampler(),
                    prepared.maxTokens(),
                    prepared.options(),
                    safeTokenConsumer(onToken),
                    safeTokenConsumer(onThinking),
                    finishHolder::set
            );
            String response = future.get();
            publishDialogueDiagnostic("STREAM_COMPLETED", request, response);
            return new LLMStreamResult(response, toStreamFinish(finishHolder.finish()));
        } catch (Exception exception) {
            env.error("[LLMService] Stream chat failed", exception);
            throw new RuntimeException("LLM stream chat failed: " + safeMessage(exception), exception);
        }
    }

    public CompletableFuture<String> submitTask(LLMRequest request) {
        return submitTask(request, null);
    }

    public CompletableFuture<String> submitTask(
            LLMRequest request,
            List<LLMPromptResultPayload.RagHitPayload> ragHitsSink
    ) {
        LlmRequestPreparer.PreparedRequest prepared = requestPreparer.prepare(request);
        copyRagHits(prepared, ragHitsSink);
        try {
            return inferenceClient.task(
                    prepared.messages(),
                    prepared.sampler(),
                    prepared.maxTokens(),
                    prepared.taskPriority(),
                    prepared.taskPreemptible(),
                    prepared.options()
            );
        } catch (Exception exception) {
            env.error("[LLMService] Task submit failed", exception);
            return CompletableFuture.failedFuture(exception);
        }
    }

    public CompletableFuture<LlmGenerationResult> submitTaskWithUsage(
            LLMRequest request,
            List<LLMPromptResultPayload.RagHitPayload> ragHitsSink
    ) {
        LlmRequestPreparer.PreparedRequest prepared = requestPreparer.prepare(request);
        copyRagHits(prepared, ragHitsSink);
        try {
            return inferenceClient.taskWithUsage(
                    prepared.messages(),
                    prepared.sampler(),
                    prepared.maxTokens(),
                    prepared.taskPriority(),
                    prepared.taskPreemptible(),
                    prepared.options()
            );
        } catch (Exception exception) {
            env.error("[LLMService] Task submit failed", exception);
            return CompletableFuture.failedFuture(exception);
        }
    }

    public CompletableFuture<String> submitTaskStream(
            LLMRequest request,
            Consumer<String> onToken,
            List<LLMPromptResultPayload.RagHitPayload> ragHitsSink
    ) {
        LlmRequestPreparer.PreparedRequest prepared = requestPreparer.prepare(request);
        copyRagHits(prepared, ragHitsSink);
        try {
            return inferenceClient.taskStream(
                    prepared.messages(),
                    prepared.sampler(),
                    prepared.maxTokens(),
                    prepared.taskPriority(),
                    prepared.taskPreemptible(),
                    prepared.options(),
                    safeTokenConsumer(onToken)
            );
        } catch (Exception exception) {
            env.error("[LLMService] Stream task submit failed", exception);
            return CompletableFuture.failedFuture(exception);
        }
    }

    public CompletableFuture<LlmGenerationResult> submitTaskStreamWithUsage(
            LLMRequest request,
            Consumer<String> onToken,
            Consumer<LLMStreamFinish> onFinish,
            List<LLMPromptResultPayload.RagHitPayload> ragHitsSink
    ) {
        return submitTaskStreamWithUsage(request, onToken, null, onFinish, ragHitsSink);
    }

    public CompletableFuture<LlmGenerationResult> submitTaskStreamWithUsage(
            LLMRequest request,
            Consumer<String> onToken,
            Consumer<String> onThinking,
            Consumer<LLMStreamFinish> onFinish,
            List<LLMPromptResultPayload.RagHitPayload> ragHitsSink
    ) {
        LlmRequestPreparer.PreparedRequest prepared = requestPreparer.prepare(request);
        copyRagHits(prepared, ragHitsSink);
        try {
            return inferenceClient.taskStreamWithUsage(
                    prepared.messages(),
                    prepared.sampler(),
                    prepared.maxTokens(),
                    prepared.taskPriority(),
                    prepared.taskPreemptible(),
                    prepared.options(),
                    safeTokenConsumer(onToken),
                    safeTokenConsumer(onThinking),
                    finish -> {
                        if (onFinish != null) {
                            onFinish.accept(toStreamFinish(finish));
                        }
                    }
            );
        } catch (Exception exception) {
            env.error("[LLMService] Stream task submit failed", exception);
            return CompletableFuture.failedFuture(exception);
        }
    }

    public RagCacheManager getRagCache() {
        return ragService.cache();
    }

    private void publishDialogueDiagnostic(String code, LLMRequest request, String response) {
        String input = request == null ? "" : request.extractMessages().stream()
                .filter(Objects::nonNull)
                .map(message -> (message.getRole() == null ? "" : message.getRole()) + ": "
                        + (message.getContent() == null ? "" : message.getContent()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        env.diagnostics().publish(DiagnosticEvent.now(
                "module.llm",
                code,
                DiagnosticSeverity.INFO,
                DiagnosticPrivacy.RAW_CONTENT,
                Map.of("input", input, "output", response == null ? "" : response)
        ));
    }

    public boolean hasCache(String uid) {
        return ragService.hasCache(uid);
    }

    public void upsertRagEntry(String uid, String entryId, String content, float[] vector) {
        ragService.upsert(uid, entryId, content, vector);
    }

    public void patchRagEntry(
            String uid,
            String entryId,
            String content,
            float[] vector,
            boolean updateContent,
            boolean updateVector
    ) {
        ragService.patch(uid, entryId, content, vector, updateContent, updateVector);
    }

    public void deleteRagEntry(String uid, String entryId) {
        ragService.delete(uid, entryId);
    }

    public void clearRagUid(String uid) {
        ragService.clear(uid);
    }

    public boolean hasRagUid(String uid) {
        return ragService.hasCache(uid);
    }

    public boolean hasRagEntry(String uid, String entryId) {
        return ragService.hasEntry(uid, entryId);
    }

    public List<RagCacheManager.RagEntrySearchResult> searchRagEntries(
            String uid,
            String queryText,
            int topK,
            float threshold
    ) {
        return ragService.searchEntries(uid, queryText, topK, threshold);
    }

    public List<RagCacheManager.RagEntrySearchResult> searchRagEntries(
            String uid,
            String queryText,
            float[] queryVector,
            int topK,
            float threshold
    ) {
        return ragService.searchEntries(uid, queryText, queryVector, topK, threshold);
    }

    public RagLibraryRegistry.RagLibraryMeta registerRagLibrary(
            String uid,
            String modid,
            String visibility,
            List<String> tags
    ) {
        return ragService.registerLibrary(uid, modid, visibility, tags);
    }

    public void unregisterRagLibrary(String uid) {
        ragService.unregisterLibrary(uid);
    }

    public RagLibraryRegistry.RagLibraryMeta ragLibrary(String uid) {
        return ragService.library(uid);
    }

    public List<RagLibrarySearchResult> searchRagLibraryByUid(String uid, String queryText, int topK, float threshold) {
        return mapLibraryResults(ragService.searchLibraryByUid(uid, queryText, topK, threshold));
    }

    public List<RagLibrarySearchResult> searchSharedRagLibrariesByModid(
            String modid,
            String queryText,
            int topK,
            float threshold
    ) {
        return mapLibraryResults(ragService.searchSharedByModid(modid, queryText, topK, threshold));
    }

    public List<RagLibrarySearchResult> searchSharedRagLibrariesByTags(
            List<String> tags,
            String queryText,
            int topK,
            float threshold
    ) {
        return mapLibraryResults(ragService.searchSharedByTags(tags, queryText, topK, threshold));
    }

    public List<RagCacheManager.RagEntrySearchResult> searchInlineRagContents(
            List<String> contents,
            String queryText,
            int topK,
            float threshold
    ) {
        return ragService.searchInline(contents, queryText, topK, threshold);
    }

    public boolean isReady() {
        return initialized && runtimeInspector.isReady();
    }

    public boolean hasChatQueueCapacity() {
        return runtimeInspector.hasChatQueueCapacity();
    }

    public boolean hasTaskQueueCapacity() {
        return runtimeInspector.hasTaskQueueCapacity();
    }

    public boolean supportsThinking() {
        return runtimeInspector.supportsThinking();
    }

    public boolean supportsMtp() {
        return runtimeInspector.supportsMtp();
    }

    public LlmMtpCapabilitySnapshot getMtpCapability() {
        return runtimeInspector.mtpCapability();
    }

    public LlmEngineCapabilitySnapshot getRuntimeCapabilities() {
        return runtimeInspector.runtimeCapabilities();
    }

    public LlmContextBudgetSnapshot getContextBudgetPlan() {
        return runtimeInspector.contextBudgetPlan();
    }

    public LlmContextBudgetSnapshot getContextBudgetPlan(String lane) {
        return runtimeInspector.contextBudgetPlan(lane);
    }

    public CompletableFuture<LlmMtpCalibrationResult> calibrateMtpAsync(LlmMtpCalibrationRequest request) {
        return runtimeInspector.calibrateMtpAsync(request);
    }

    public float[] embed(String text) throws Exception {
        return runtimeInspector.embed(text);
    }

    public float[][] embed(List<String> texts) throws Exception {
        return runtimeInspector.embed(texts);
    }

    public int getEmbeddingDimension() {
        return runtimeInspector.embeddingDimension();
    }

    public LLMPrimitiveResultPayload tokenCountResponse(String requestId, LLMRequest request) {
        if (!inferenceClient.supportsTokenCounting()) {
            return LLMPrimitiveResultPayload.failed(
                    requestId,
                    LLMPrimitiveQueryPayload.QUERY_TYPE_TOKEN_COUNT,
                    "LLM_TOKENIZER_UNAVAILABLE",
                    "The active generation backend does not provide a tokenizer"
            );
        }
        try {
            LlmRequestPreparer.PreparedRequest prepared = requestPreparer.prepareTokenCount(request);
            return LLMPrimitiveResultPayload.tokenCount(
                    requestId,
                    inferenceClient.countChatPromptTokens(prepared.messages(), prepared.sampler())
            );
        } catch (Exception exception) {
            return LLMPrimitiveResultPayload.failed(
                    requestId,
                    LLMPrimitiveQueryPayload.QUERY_TYPE_TOKEN_COUNT,
                    exception instanceof UnsupportedOperationException
                            ? "LLM_TOKEN_COUNT_UNSUPPORTED_INPUT"
                            : "LLM_TOKEN_COUNT_FAILED",
                    safeMessage(exception)
            );
        }
    }

    public LLMPrimitiveResultPayload embedResponse(
            String requestId,
            List<String> texts,
            boolean includeVector,
            boolean includeEmbeddingDetails
    ) {
        try {
            List<LLMPrimitiveResultPayload.EmbedResultPayload> results = new java.util.ArrayList<>();
            if (texts != null) {
                float[][] vectors = embed(texts);
                for (int index = 0; index < texts.size(); index++) {
                    float[] vector = vectors != null && index < vectors.length ? vectors[index] : null;
                    results.add(LLMPrimitiveResultPayload.EmbedResultPayload.of(
                            texts.get(index),
                            vector,
                            includeVector,
                            runtimeInspector.embeddingModelName(),
                            runtimeInspector.embeddingNamespace()
                    ));
                }
            }
            if (!includeEmbeddingDetails) {
                results = new java.util.ArrayList<>(results.stream()
                        .map(result -> new LLMPrimitiveResultPayload.EmbedResultPayload(
                                result.text(),
                                result.dimension(),
                                new float[0],
                                result.embeddingModelName(),
                                result.embeddingNamespace()
                        ))
                        .toList());
            }
            return LLMPrimitiveResultPayload.embed(requestId, results);
        } catch (Exception exception) {
            return LLMPrimitiveResultPayload.failed(
                    requestId,
                    LLMPrimitiveQueryPayload.QUERY_TYPE_EMBED,
                    "LLM_EMBED_FAILED",
                    safeMessage(exception)
            );
        }
    }

    public LLMPrimitiveResultPayload runtimeSnapshotResponse(String requestId) {
        return runtimeSnapshotResponse(requestId, true);
    }

    public LLMPrimitiveResultPayload runtimeSnapshotResponse(String requestId, boolean includeRuntimeDetails) {
        return LLMPrimitiveResultPayload.runtime(requestId, toRuntimeSnapshot(includeRuntimeDetails));
    }

    public LLMRuntimeSnapshotPayload toRuntimeSnapshot() {
        return toRuntimeSnapshot(true);
    }

    public LLMRuntimeSnapshotPayload toRuntimeSnapshot(boolean includeRuntimeDetails) {
        return runtimeInspector.snapshot(includeRuntimeDetails);
    }

    public void shutdown() {
        ragService.shutdown();
        env.info("[LLMService] Shutdown complete");
    }

    public static LLMPromptResultPayload.TokenUsagePayload toUsagePayload(LlmTokenUsage usage) {
        if (usage == null) {
            return LLMPromptResultPayload.TokenUsagePayload.empty();
        }
        return new LLMPromptResultPayload.TokenUsagePayload(
                usage.promptTokens(),
                usage.completionTokens(),
                usage.thinkingTokens(),
                usage.outputTokens(),
                usage.totalTokens()
        );
    }

    private List<RagLibrarySearchResult> mapLibraryResults(List<LlmRagService.LibrarySearchResult> results) {
        return results == null || results.isEmpty()
                ? List.of()
                : results.stream()
                        .map(result -> new RagLibrarySearchResult(result.uid(), result.library(), result.entries()))
                        .toList();
    }

    private void copyRagHits(
            LlmRequestPreparer.PreparedRequest prepared,
            List<LLMPromptResultPayload.RagHitPayload> sink
    ) {
        if (sink != null) {
            sink.addAll(prepared.ragHits());
        }
    }

    private Consumer<String> safeTokenConsumer(Consumer<String> consumer) {
        return token -> {
            if (consumer != null && token != null) {
                consumer.accept(token);
            }
        };
    }

    private static LLMStreamFinish toStreamFinish(LlmStreamFinish finish) {
        if (finish == null) {
            return LLMStreamFinish.completed(LLMPromptResultPayload.TokenUsagePayload.empty());
        }
        return new LLMStreamFinish(
                finish.type() == null ? "COMPLETED" : finish.type().name(),
                toUsagePayload(finish.usage()),
                finish.error(),
                finish.thinkingContent()
        );
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    public record LLMResult(
            String text,
            String thinkingContent,
            List<LLMPromptResultPayload.RagHitPayload> ragHits,
            LLMPromptResultPayload.TokenUsagePayload usage
    ) {
        public LLMResult {
            text = text == null ? "" : text;
            thinkingContent = thinkingContent == null ? "" : thinkingContent;
            ragHits = ragHits == null ? List.of() : List.copyOf(ragHits);
            usage = usage == null ? LLMPromptResultPayload.TokenUsagePayload.empty() : usage;
        }

        public LLMResult(
                String text,
                List<LLMPromptResultPayload.RagHitPayload> ragHits,
                LLMPromptResultPayload.TokenUsagePayload usage
        ) {
            this(text, "", ragHits, usage);
        }
    }

    public record LLMStreamResult(String text, LLMStreamFinish finish) {
        public LLMStreamResult {
            text = text == null ? "" : text;
            finish = finish == null
                    ? LLMStreamFinish.completed(LLMPromptResultPayload.TokenUsagePayload.empty())
                    : finish;
        }
    }

    public record LLMStreamFinish(
            String type,
            LLMPromptResultPayload.TokenUsagePayload usage,
            Throwable error,
            String thinkingContent
    ) {
        public LLMStreamFinish(String type, LLMPromptResultPayload.TokenUsagePayload usage, Throwable error) {
            this(type, usage, error, "");
        }

        public LLMStreamFinish {
            type = type == null || type.isBlank() ? "COMPLETED" : type.trim().toUpperCase();
            usage = usage == null ? LLMPromptResultPayload.TokenUsagePayload.empty() : usage;
            thinkingContent = thinkingContent == null ? "" : thinkingContent;
        }

        static LLMStreamFinish completed(LLMPromptResultPayload.TokenUsagePayload usage) {
            return new LLMStreamFinish("COMPLETED", usage, null);
        }
    }

    public record RagLibrarySearchResult(
            String uid,
            RagLibraryRegistry.RagLibraryMeta library,
            List<RagCacheManager.RagEntrySearchResult> entries
    ) {
        public RagLibrarySearchResult {
            uid = uid == null ? "" : uid.trim();
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    private static final class LlmStreamFinishHolder {
        private volatile LlmStreamFinish finish;

        void set(LlmStreamFinish finish) {
            this.finish = finish;
        }

        LlmStreamFinish finish() {
            return finish;
        }
    }

    public static class Builder {
        private IGameEnvironment env;
        private LlmConfiguration config;
        private LlmInferenceClient inferenceClient;
        private LlmInferenceGovernor inferenceGovernor;
        private LlmPerformanceProvider performanceProvider = LlmPerformanceProvider.UNAVAILABLE;
        private boolean usePersistentCache = true;
        private Path cacheDirectory;
        private String cacheNamespace = "default";
        private boolean embeddingConfigured;
        private Executor ragSearchExecutor = Runnable::run;
        private RagPersistenceScheduler ragPersistenceScheduler = RagPersistenceScheduler.immediate();
        private LlmRagStorageService ragStorageService;

        public Builder env(IGameEnvironment env) {
            this.env = env;
            return this;
        }

        public Builder config(LlmConfiguration config) {
            this.config = config;
            return this;
        }

        public Builder aiService(JavaLlamaServer aiService) {
            this.inferenceClient = new JavaLlamaInferenceClient(aiService);
            return this;
        }

        public Builder inferenceClient(LlmInferenceClient inferenceClient) {
            this.inferenceClient = inferenceClient;
            return this;
        }

        public Builder inferenceGovernor(LlmInferenceGovernor inferenceGovernor) {
            this.inferenceGovernor = inferenceGovernor;
            return this;
        }

        public Builder performanceProvider(LlmPerformanceProvider performanceProvider) {
            this.performanceProvider = performanceProvider == null
                    ? LlmPerformanceProvider.UNAVAILABLE
                    : performanceProvider;
            return this;
        }

        public Builder usePersistentCache(boolean usePersistentCache) {
            this.usePersistentCache = usePersistentCache;
            return this;
        }

        public Builder cacheDirectory(Path cacheDirectory) {
            this.cacheDirectory = cacheDirectory;
            return this;
        }

        public Builder cacheNamespace(String cacheNamespace) {
            this.cacheNamespace = cacheNamespace;
            return this;
        }

        public Builder embeddingConfigured(boolean embeddingConfigured) {
            this.embeddingConfigured = embeddingConfigured;
            return this;
        }

        public Builder ragSearchExecutor(Executor ragSearchExecutor) {
            this.ragSearchExecutor = ragSearchExecutor == null ? Runnable::run : ragSearchExecutor;
            return this;
        }

        public Builder ragPersistenceScheduler(RagPersistenceScheduler ragPersistenceScheduler) {
            this.ragPersistenceScheduler = ragPersistenceScheduler == null
                    ? RagPersistenceScheduler.immediate()
                    : ragPersistenceScheduler;
            return this;
        }

        public Builder ragStorage(LlmRagStorageService ragStorageService) {
            this.ragStorageService = ragStorageService;
            return this;
        }

        public LLMService build() {
            Objects.requireNonNull(env, "env must be set");
            Objects.requireNonNull(inferenceClient, "inferenceClient must be set");
            if (usePersistentCache) {
                Objects.requireNonNull(cacheDirectory, "cacheDirectory must be set when persistent cache is enabled");
            }
            return new LLMService(this);
        }
    }
}
