package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.LlmGenerationResult;
import com.rheinmetal.tianshu.libs.llm.LlmStreamFinish;
import com.rheinmetal.tianshu.libs.llm.LlmTokenUsage;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationRequest;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCapabilitySnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmPerformanceProvider;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMRuntimeSnapshotPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class LLMService {

    private static final int DEFAULT_RAG_TOP_K = 4;
    private static final float DEFAULT_RAG_THRESHOLD = 0.7f;

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final LlmInferenceClient inferenceClient;
    private final LlmInferenceGovernor inferenceGovernor;
    private final EmbeddingService embeddingService;
    private final RagCacheManager worldRagCache;
    private final RagCacheManager globalRagCache;
    private volatile boolean initialized = false;

    private LLMService(Builder builder) {
        this.env = Objects.requireNonNull(builder.env, "env");
        this.config = builder.config != null ? builder.config : new DefaultLlmConfig();
        this.inferenceClient = Objects.requireNonNull(builder.inferenceClient, "inferenceClient");
        this.inferenceGovernor = builder.inferenceGovernor != null
                ? builder.inferenceGovernor
                : new LlmInferenceGovernor(config, builder.performanceProvider);

        EmbeddingService embeddingAdapter = new ClientEmbeddingAdapter(inferenceClient);
        this.embeddingService = embeddingAdapter;
        if (builder.usePersistentCache) {
            this.worldRagCache = new PersistentRagCacheManager(env, embeddingAdapter, builder.cacheDirectory, builder.cacheNamespace);
            this.globalRagCache = new PersistentRagCacheManager(env, embeddingAdapter, builder.globalCacheDirectory(), builder.cacheNamespace);
        } else {
            this.worldRagCache = new DefaultRagCacheManager(env, embeddingAdapter);
            this.globalRagCache = new DefaultRagCacheManager(env, embeddingAdapter);
        }

        this.initialized = true;
        env.info("[LLMService] Initialized, cache mode: " + (builder.usePersistentCache ? "PERSISTENT" : "MEMORY"));
    }

    public static Builder builder() {
        return new Builder();
    }

    public String chat(String userMessage, String systemPrompt) {
        LLMRequest req = LLMRequest.of(
                Chunk.message(MessageItem.system(systemPrompt), MessageItem.user(userMessage))
        );
        return chat(req).text();
    }

    public LLMResult chat(LLMRequest request) {
        PreparedResult prepared = prepareRequest(request);
        try {
            LlmGenerationResult result = inferenceClient.chatWithUsage(prepared.messages(), prepared.sampler(), prepared.maxTokens(), prepared.options());
            return new LLMResult(result == null ? "" : result.text(), prepared.ragHits(), toUsagePayload(result == null ? null : result.usage()));
        } catch (Exception e) {
            env.error("[LLMService] Chat failed", e);
            throw new RuntimeException("LLM chat failed: " + safeMessage(e), e);
        }
    }

    public void chatStream(LLMRequest request, Consumer<String> onToken) {
        chatStream(request, onToken, null);
    }

    public LLMStreamResult chatStream(LLMRequest request, Consumer<String> onToken, List<LLMPromptResultPayload.RagHitPayload> ragHitsSink) {
        PreparedResult prepared = prepareRequest(request);
        copyRagHits(prepared, ragHitsSink);
        try {
            LlmStreamFinishHolder finishHolder = new LlmStreamFinishHolder();
            CompletableFuture<String> future = inferenceClient.chatStreamWithUsage(
                    prepared.messages(),
                    prepared.sampler(),
                    prepared.maxTokens(),
                    prepared.options(),
                    safeTokenConsumer(onToken),
                    finishHolder::set
            );
            String text = future.get();
            return new LLMStreamResult(text, toStreamFinish(finishHolder.finish()));
        } catch (Exception e) {
            env.error("[LLMService] Stream chat failed", e);
            throw new RuntimeException("LLM stream chat failed: " + safeMessage(e), e);
        }
    }

    public CompletableFuture<String> submitTask(LLMRequest request) {
        return submitTask(request, null);
    }

    public CompletableFuture<String> submitTask(LLMRequest request, List<LLMPromptResultPayload.RagHitPayload> ragHitsSink) {
        PreparedResult prepared = prepareRequest(request);
        copyRagHits(prepared, ragHitsSink);
        try {
            return inferenceClient.task(prepared.messages(), prepared.sampler(), prepared.maxTokens(),
                    prepared.taskPriority(), prepared.taskPreemptible(), prepared.options());
        } catch (Exception e) {
            env.error("[LLMService] Task submit failed", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<LlmGenerationResult> submitTaskWithUsage(LLMRequest request, List<LLMPromptResultPayload.RagHitPayload> ragHitsSink) {
        PreparedResult prepared = prepareRequest(request);
        copyRagHits(prepared, ragHitsSink);
        try {
            return inferenceClient.taskWithUsage(prepared.messages(), prepared.sampler(), prepared.maxTokens(),
                    prepared.taskPriority(), prepared.taskPreemptible(), prepared.options());
        } catch (Exception e) {
            env.error("[LLMService] Task submit failed", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<String> submitTaskStream(LLMRequest request, Consumer<String> onToken, List<LLMPromptResultPayload.RagHitPayload> ragHitsSink) {
        PreparedResult prepared = prepareRequest(request);
        copyRagHits(prepared, ragHitsSink);
        try {
            return inferenceClient.taskStream(prepared.messages(), prepared.sampler(), prepared.maxTokens(),
                    prepared.taskPriority(), prepared.taskPreemptible(), prepared.options(), safeTokenConsumer(onToken));
        } catch (Exception e) {
            env.error("[LLMService] Stream task submit failed", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<LlmGenerationResult> submitTaskStreamWithUsage(LLMRequest request, Consumer<String> onToken, Consumer<LLMStreamFinish> onFinish, List<LLMPromptResultPayload.RagHitPayload> ragHitsSink) {
        PreparedResult prepared = prepareRequest(request);
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
                    finish -> {
                        if (onFinish != null) {
                            onFinish.accept(toStreamFinish(finish));
                        }
                    }
            );
        } catch (Exception e) {
            env.error("[LLMService] Stream task submit failed", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public RagCacheManager getRagCache() {
        return worldRagCache;
    }

    public RagCacheManager getGlobalRagCache() {
        return globalRagCache;
    }

    public void indexCache(String uid, List<String> contents) {
        indexCache(uid, contents, false);
    }

    public void indexCache(String uid, List<String> contents, boolean globalRagCache) {
        ragCache(globalRagCache).index(uid, contents);
    }

    public void evictCache(String uid) {
        evictCache(uid, false);
    }

    public void evictCache(String uid, boolean globalRagCache) {
        ragCache(globalRagCache).evict(uid);
    }

    public void evictCache(String uid, String content) {
        evictCache(uid, content, false);
    }

    public void evictCache(String uid, String content, boolean globalRagCache) {
        ragCache(globalRagCache).evict(uid, content);
    }

    public boolean hasCache(String uid) {
        return hasCache(uid, false);
    }

    public boolean hasCache(String uid, boolean globalRagCache) {
        return ragCache(globalRagCache).hasCache(uid);
    }

    public boolean isReady() {
        return initialized && inferenceClient.isReady();
    }

    public boolean hasChatQueueCapacity() {
        return inferenceClient.hasChatQueueCapacity();
    }

    public boolean hasTaskQueueCapacity() {
        return inferenceClient.hasTaskQueueCapacity();
    }

    public boolean supportsEnableThinking() {
        return inferenceClient.supportsEnableThinking();
    }

    public boolean supportsMtp() {
        return inferenceClient.supportsMtp();
    }

    public LlmMtpCapabilitySnapshot getMtpCapability() {
        return inferenceClient.getMtpCapability();
    }

    public CompletableFuture<LlmMtpCalibrationResult> calibrateMtpAsync(LlmMtpCalibrationRequest request) {
        return inferenceClient.calibrateMtpAsync(request);
    }

    public float[] embed(String text) throws Exception {
        return inferenceClient.embed(text);
    }

    public float[][] embed(List<String> texts) throws Exception {
        return inferenceClient.embed(texts);
    }

    public int getEmbeddingDimension() {
        return Math.max(0, embeddingService.getEmbeddingDimension());
    }

    public LLMPrimitiveResultPayload tokenCountResponse(String requestId, LLMRequest request) {
        try {
            PreparedResult prepared = prepareRequest(request);
            return LLMPrimitiveResultPayload.tokenCount(requestId, inferenceClient.countChatPromptTokens(prepared.messages(), prepared.sampler()));
        } catch (Exception e) {
            return LLMPrimitiveResultPayload.failed(
                    requestId,
                    LLMPrimitiveQueryPayload.QUERY_TYPE_TOKEN_COUNT,
                    "LLM_TOKEN_COUNT_FAILED",
                    safeMessage(e)
            );
        }
    }

    public LLMPrimitiveResultPayload embedResponse(String requestId, List<String> texts, boolean includeVector, boolean includeEmbeddingDetails) {
        try {
            List<LLMPrimitiveResultPayload.EmbedResultPayload> results = new ArrayList<>();
            if (texts != null) {
                float[][] vectors = embed(texts);
                for (int i = 0; i < texts.size(); i++) {
                    String text = texts.get(i);
                    float[] vector = vectors != null && i < vectors.length ? vectors[i] : null;
                    results.add(LLMPrimitiveResultPayload.EmbedResultPayload.of(text, vector, includeVector));
                }
            }
            if (!includeEmbeddingDetails) {
                results = results.stream()
                        .map(result -> new LLMPrimitiveResultPayload.EmbedResultPayload(result.text(), result.dimension(), new float[0]))
                        .toList();
            }
            return LLMPrimitiveResultPayload.embed(requestId, results);
        } catch (Exception e) {
            return LLMPrimitiveResultPayload.failed(requestId, LLMPrimitiveQueryPayload.QUERY_TYPE_EMBED, "LLM_EMBED_FAILED", safeMessage(e));
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
        boolean ready = isReady();
        int embeddingDimension = getEmbeddingDimension();
        boolean hasEmbedding = inferenceClient != null && inferenceClient.isReady() && embeddingDimension > 0;
        LlmMtpCapabilitySnapshot mtp = getMtpCapability();
        String modelName = includeRuntimeDetails ? configName() : "";
        String modelProfile = includeRuntimeDetails ? modelProfile() : "";
        String failureMessage = includeRuntimeDetails ? "" : "";
        return new LLMRuntimeSnapshotPayload(
                ready,
                inferenceClient != null && inferenceClient.isReady(),
                hasEmbedding,
                embeddingDimension,
                supportsMtp(),
                mtp != null && mtp.supported() && mtp.calibrated(),
                mtp == null ? 0 : mtp.mtpLayerCount(),
                mtp == null ? 0 : mtp.recommendedDraftMax(),
                Math.max(0, config.getLlmContextSize()),
                hasChatQueueCapacity(),
                hasTaskQueueCapacity(),
                inferenceClient != null && inferenceClient.hasQueueCapacity(),
                inferenceClient != null ? inferenceClient.getChatQueueSize() : 0,
                inferenceClient != null ? inferenceClient.getTaskQueueSize() : 0,
                inferenceClient != null ? inferenceClient.getQueueSize() : 0,
                modelName,
                modelProfile,
                failureMessage,
                System.currentTimeMillis()
        );
    }

    public void shutdown() {
        env.info("[LLMService] Shutdown complete");
    }

    private PreparedResult prepareRequest(LLMRequest request) {
        LLMRequest effectiveRequest = request != null ? request : new LLMRequest();
        List<MessageItem> orderedMessages = new ArrayList<>();
        List<LLMPromptResultPayload.RagHitPayload> ragHits = new ArrayList<>();
        String lastUserMessage = extractLastUserMessage(effectiveRequest);

        for (Chunk chunk : effectiveRequest.getChunks()) {
            if (chunk == null || chunk.getType() == null) {
                continue;
            }
            if ("message".equalsIgnoreCase(chunk.getType())) {
                appendMessages(orderedMessages, chunk.getMessageContent());
            } else if ("rag".equalsIgnoreCase(chunk.getType())) {
                RagPreparation rag = processRagChunk(chunk, lastUserMessage);
                collectRagHits(chunk, rag.results(), ragHits);
                if (!rag.prompt().isEmpty()) {
                    orderedMessages.add(MessageItem.system(rag.prompt()));
                }
            }
        }

        return new PreparedResult(
                buildLibsMessages(orderedMessages),
                createSampler(effectiveRequest),
                maxTokens(effectiveRequest),
                effectiveRequest.getTaskPriority(),
                effectiveRequest.getTaskPreemptible(),
                inferenceGovernor.resolve(effectiveRequest.getInferencePolicy(), effectiveRequest.isTaskLane(), supportsMtp()),
                ragHits
        );
    }

    private void appendMessages(List<MessageItem> sink, List<MessageItem> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (MessageItem message : messages) {
            if (message == null || message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            String role = normalizeRole(message.getRole());
            sink.add(new MessageItem(role, message.getContent()));
        }
    }

    private RagPreparation processRagChunk(Chunk ragChunk, String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return RagPreparation.empty();
        }

        List<RagSearchResult> results = searchRag(ragChunk, queryText);
        List<RagSearchResult> budgeted = applyRagBudget(results, ragChunk.getMemoryRagTokenBudget());
        return new RagPreparation(budgeted, buildRagPrompt(budgeted, ragChunk.getPrompt()));
    }

    private List<RagSearchResult> searchRag(Chunk ragChunk, String queryText) {
        String uid = ragChunk.getUid();
        boolean useCache = Boolean.TRUE.equals(ragChunk.getUseCache());
        List<String> contents = validTexts(ragChunk.getRagContent());

        if (useCache) {
            if (!contents.isEmpty()) {
                ragCache(ragChunk).index(uid, contents);
            }
            return ragCache(ragChunk).search(uid, queryText, DEFAULT_RAG_TOP_K, DEFAULT_RAG_THRESHOLD);
        }

        if (contents.isEmpty()) {
            return List.of();
        }
        try {
            return inferenceClient.search(queryText, contents, DEFAULT_RAG_TOP_K, DEFAULT_RAG_THRESHOLD);
        } catch (Exception e) {
            env.error("[LLMService] Search via libs failed", e);
            return List.of();
        }
    }

    private List<RagSearchResult> applyRagBudget(List<RagSearchResult> results, Integer tokenBudget) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return results;
    }

    private void collectRagHits(Chunk chunk, List<RagSearchResult> results, List<LLMPromptResultPayload.RagHitPayload> ragHits) {
        if (chunk == null || !Boolean.TRUE.equals(chunk.getIncludeRagHits()) || results == null || results.isEmpty()) {
            return;
        }

        List<LLMPromptResultPayload.HitEntry> entries = new ArrayList<>();
        for (RagSearchResult r : results) {
            entries.add(LLMPromptResultPayload.HitEntry.of(r.getScore(), r.getContent()));
        }
        ragHits.add(LLMPromptResultPayload.RagHitPayload.of(chunk.getUid(), chunk.isGlobalRagCache(), entries));
    }

    private String extractLastUserMessage(LLMRequest request) {
        for (int i = request.getChunks().size() - 1; i >= 0; i--) {
            Chunk chunk = request.getChunks().get(i);
            if (chunk != null && "message".equalsIgnoreCase(chunk.getType()) && chunk.getMessageContent() != null) {
                for (int j = chunk.getMessageContent().size() - 1; j >= 0; j--) {
                    MessageItem msg = chunk.getMessageContent().get(j);
                    if (msg != null && "user".equalsIgnoreCase(msg.getRole()) && msg.getContent() != null && !msg.getContent().isBlank()) {
                        return msg.getContent();
                    }
                }
            }
        }
        return null;
    }

    private String buildRagPrompt(List<RagSearchResult> results, String prompt) {
        if (results == null || results.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        if (prompt != null && !prompt.isBlank()) {
            sb.append(prompt).append('\n');
        } else {
            sb.append("以下是与当前请求相关的检索上下文，只在相关时使用：\n");
        }

        for (int i = 0; i < results.size(); i++) {
            sb.append(i + 1).append(". ").append(results.get(i).getContent()).append('\n');
        }
        return sb.toString();
    }

    private List<ChatMessage> buildLibsMessages(List<MessageItem> orderedMessages) {
        return orderedMessages.stream()
                .map(m -> new ChatMessage(m.getRole(), m.getContent()))
                .toList();
    }

    private SamplerConfig createSampler(LLMRequest request) {
        SamplerConfig config = SamplerConfig.defaults();
        Float temp = request.getTemperature();
        if (temp != null) {
            config.setTemperature(temp);
        }
        config.setEnableThinking(Boolean.TRUE.equals(request.getThinking()));
        return config;
    }

    private int maxTokens(LLMRequest request) {
        Integer maxTokens = request.getMaxTokens();
        return maxTokens != null && maxTokens > 0 ? maxTokens : 0;
    }

    private void copyRagHits(PreparedResult prepared, List<LLMPromptResultPayload.RagHitPayload> ragHitsSink) {
        if (ragHitsSink != null) {
            ragHitsSink.addAll(prepared.ragHits());
        }
    }

    private Consumer<String> safeTokenConsumer(Consumer<String> onToken) {
        return token -> {
            if (onToken != null && token != null) {
                onToken.accept(token);
            }
        };
    }

    private static List<String> validTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return texts.stream()
                .filter(text -> text != null && !text.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private RagCacheManager ragCache(Chunk chunk) {
        return ragCache(chunk != null && chunk.isGlobalRagCache());
    }

    private RagCacheManager ragCache(boolean globalRagCache) {
        return globalRagCache ? this.globalRagCache : this.worldRagCache;
    }

    private static String normalizeRole(String role) {
        if (role == null) {
            return "user";
        }
        String normalized = role.trim().toLowerCase();
        return switch (normalized) {
            case "system", "assistant", "user" -> normalized;
            default -> "user";
        };
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
    }

    private String configName() {
        return safeText(config.getCustomLlmName());
    }

    private String modelProfile() {
        return "";
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    public static LLMPromptResultPayload.TokenUsagePayload toUsagePayload(LlmTokenUsage usage) {
        if (usage == null) {
            return LLMPromptResultPayload.TokenUsagePayload.empty();
        }
        return new LLMPromptResultPayload.TokenUsagePayload(
                usage.promptTokens(),
                usage.completionTokens(),
                usage.totalTokens()
        );
    }

    private static LLMStreamFinish toStreamFinish(LlmStreamFinish finish) {
        if (finish == null) {
            return LLMStreamFinish.completed(LLMPromptResultPayload.TokenUsagePayload.empty());
        }
        String type = finish.type() == null ? "COMPLETED" : finish.type().name();
        return new LLMStreamFinish(type, toUsagePayload(finish.usage()), finish.error());
    }

    private record PreparedResult(
            List<ChatMessage> messages,
            SamplerConfig sampler,
            int maxTokens,
            int taskPriority,
            boolean taskPreemptible,
            LlmInferenceOptions options,
            List<LLMPromptResultPayload.RagHitPayload> ragHits
    ) {
        private PreparedResult {
            messages = messages != null ? List.copyOf(messages) : List.of();
            options = options == null ? LlmInferenceOptions.defaults() : options;
            ragHits = ragHits != null ? List.copyOf(ragHits) : List.of();
        }
    }

    private record RagPreparation(List<RagSearchResult> results, String prompt) {
        private RagPreparation {
            results = results != null ? List.copyOf(results) : List.of();
            prompt = prompt != null ? prompt : "";
        }

        static RagPreparation empty() {
            return new RagPreparation(List.of(), "");
        }
    }

    private static final class ClientEmbeddingAdapter implements EmbeddingService {
        private final LlmInferenceClient inferenceClient;
        private volatile int cachedDimension = -1;

        ClientEmbeddingAdapter(LlmInferenceClient inferenceClient) {
            this.inferenceClient = inferenceClient;
        }

        @Override
        public float[] embed(String text) throws Exception {
            float[] result = inferenceClient.embed(text);
            updateDimension(result);
            return result;
        }

        @Override
        public float[][] embed(List<String> texts) throws Exception {
            float[][] result = inferenceClient.embed(texts);
            if (result != null) {
                for (float[] vector : result) {
                    updateDimension(vector);
                    if (cachedDimension > 0) {
                        break;
                    }
                }
            }
            return result;
        }

        @Override
        public int getEmbeddingDimension() {
            return cachedDimension;
        }

        private void updateDimension(float[] vector) {
            if (cachedDimension < 0 && vector != null && vector.length > 0) {
                cachedDimension = vector.length;
            }
        }
    }

    public record LLMResult(String text, List<LLMPromptResultPayload.RagHitPayload> ragHits, LLMPromptResultPayload.TokenUsagePayload usage) {
        public LLMResult {
            text = text != null ? text : "";
            ragHits = ragHits != null ? List.copyOf(ragHits) : List.of();
            usage = usage == null ? LLMPromptResultPayload.TokenUsagePayload.empty() : usage;
        }
    }

    public record LLMStreamResult(String text, LLMStreamFinish finish) {
        public LLMStreamResult {
            text = text != null ? text : "";
            finish = finish == null ? LLMStreamFinish.completed(LLMPromptResultPayload.TokenUsagePayload.empty()) : finish;
        }
    }

    public record LLMStreamFinish(String type, LLMPromptResultPayload.TokenUsagePayload usage, Throwable error) {
        public LLMStreamFinish {
            type = type == null || type.isBlank() ? "COMPLETED" : type.trim().toUpperCase();
            usage = usage == null ? LLMPromptResultPayload.TokenUsagePayload.empty() : usage;
        }

        static LLMStreamFinish completed(LLMPromptResultPayload.TokenUsagePayload usage) {
            return new LLMStreamFinish("COMPLETED", usage, null);
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
        private com.rheinmetal.tianshu.api.ITianshuConfig config;
        private LlmInferenceClient inferenceClient;
        private LlmInferenceGovernor inferenceGovernor;
        private LlmPerformanceProvider performanceProvider = LlmPerformanceProvider.UNAVAILABLE;
        private boolean usePersistentCache = true;
        private java.nio.file.Path cacheDirectory;
        private java.nio.file.Path globalCacheDirectory;
        private String cacheNamespace = "default";

        public Builder env(IGameEnvironment env) {
            this.env = env;
            return this;
        }

        public Builder config(com.rheinmetal.tianshu.api.ITianshuConfig config) {
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
            this.performanceProvider = performanceProvider == null ? LlmPerformanceProvider.UNAVAILABLE : performanceProvider;
            return this;
        }

        public Builder usePersistentCache(boolean usePersistentCache) {
            this.usePersistentCache = usePersistentCache;
            return this;
        }

        public Builder cacheDirectory(java.nio.file.Path cacheDirectory) {
            this.cacheDirectory = cacheDirectory;
            return this;
        }

        public Builder globalCacheDirectory(java.nio.file.Path globalCacheDirectory) {
            this.globalCacheDirectory = globalCacheDirectory;
            return this;
        }

        public Builder cacheNamespace(String cacheNamespace) {
            this.cacheNamespace = cacheNamespace;
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

        private java.nio.file.Path globalCacheDirectory() {
            if (globalCacheDirectory != null) {
                return globalCacheDirectory;
            }
            java.nio.file.Path parent = cacheDirectory == null ? null : cacheDirectory.getParent();
            return parent == null ? java.nio.file.Path.of("global") : parent.resolve("global");
        }
    }

    private static final class DefaultLlmConfig implements com.rheinmetal.tianshu.api.ITianshuConfig {
        @Override public boolean isAiEnabled() { return true; }
        @Override public void setAiEnabled(boolean enabled) {}
        @Override public com.rheinmetal.tianshu.constant.TriggerMode getTriggerMode() { return com.rheinmetal.tianshu.constant.TriggerMode.PUSH_TO_TALK; }
        @Override public void setTriggerMode(com.rheinmetal.tianshu.constant.TriggerMode mode) {}
        @Override public int getAsrPort() { return 0; }
        @Override public int getLlmPort() { return 0; }
        @Override public int getTtsPort() { return 0; }
        @Override public String getCustomAsrName() { return ""; }
        @Override public void setCustomAsrName(String name) {}
        @Override public String getCustomLlmName() { return ""; }
        @Override public void setCustomLlmName(String name) {}
        @Override public String getCustomTtsName() { return ""; }
        @Override public void setCustomTtsName(String name) {}
        @Override public java.nio.file.Path getRootPath() { return java.nio.file.Path.of("."); }
        @Override public java.nio.file.Path getGameConfigDir() { return java.nio.file.Path.of("."); }
        @Override public java.nio.file.Path getAsrBasePath() { return java.nio.file.Path.of("."); }
        @Override public java.nio.file.Path getLlmBasePath() { return java.nio.file.Path.of("."); }
        @Override public java.nio.file.Path getTtsBasePath() { return java.nio.file.Path.of("."); }
        @Override public java.nio.file.Path getAsrModelPath() { return null; }
        @Override public java.nio.file.Path getLlmModelPath() { return null; }
        @Override public java.nio.file.Path getTtsModelPath() { return null; }
        @Override public java.nio.file.Path getLlmGgufFilePath() { return null; }
        @Override public java.nio.file.Path getVoiceLibraryPath() { return java.nio.file.Path.of("."); }
        @Override public void save() {}
    }
}
