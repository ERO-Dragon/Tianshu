package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.LlmGenerationResult;
import com.rheinmetal.tianshu.libs.llm.LlmStreamFinish;
import com.rheinmetal.tianshu.libs.llm.LlmTokenUsage;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationRequest;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCapabilitySnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmContextBudgetSnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmEngineCapabilitySnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmPerformanceProvider;
import com.rheinmetal.tianshu.model.LlmModelInfo;
import com.rheinmetal.tianshu.model.LlmModelManager;
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
    private final RagCacheManager ragCache;
    private final RagLibraryRegistry ragLibraryRegistry;
    private final String cacheNamespace;
    private final boolean embeddingConfigured;
    private volatile boolean initialized = false;

    private LLMService(Builder builder) {
        this.env = Objects.requireNonNull(builder.env, "env");
        this.config = builder.config != null ? builder.config : new DefaultLlmConfig();
        this.inferenceClient = Objects.requireNonNull(builder.inferenceClient, "inferenceClient");
        this.inferenceGovernor = builder.inferenceGovernor != null
                ? builder.inferenceGovernor
                : new LlmInferenceGovernor(config, builder.performanceProvider);
        this.cacheNamespace = safeText(builder.cacheNamespace);
        this.embeddingConfigured = builder.embeddingConfigured;

        EmbeddingService embeddingAdapter = new ClientEmbeddingAdapter(inferenceClient);
        this.embeddingService = embeddingAdapter;
        if (builder.usePersistentCache) {
            this.ragCache = new PersistentRagCacheManager(env, embeddingAdapter, builder.cacheDirectory, builder.cacheNamespace);
        } else {
            this.ragCache = new DefaultRagCacheManager(env, embeddingAdapter);
        }
        this.ragLibraryRegistry = new RagLibraryRegistry(env, builder.usePersistentCache ? builder.cacheDirectory : null);

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
            return new LLMResult(
                    result == null ? "" : result.text(),
                    result == null ? "" : result.thinkingContent(),
                    prepared.ragHits(),
                    toUsagePayload(result == null ? null : result.usage())
            );
        } catch (Exception e) {
            env.error("[LLMService] Chat failed", e);
            throw new RuntimeException("LLM chat failed: " + safeMessage(e), e);
        }
    }

    public void chatStream(LLMRequest request, Consumer<String> onToken) {
        chatStream(request, onToken, null);
    }

    public LLMStreamResult chatStream(LLMRequest request, Consumer<String> onToken, List<LLMPromptResultPayload.RagHitPayload> ragHitsSink) {
        return chatStream(request, onToken, null, ragHitsSink);
    }

    public LLMStreamResult chatStream(LLMRequest request, Consumer<String> onToken, Consumer<String> onThinking, List<LLMPromptResultPayload.RagHitPayload> ragHitsSink) {
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
                    safeTokenConsumer(onThinking),
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
        return submitTaskStreamWithUsage(request, onToken, null, onFinish, ragHitsSink);
    }

    public CompletableFuture<LlmGenerationResult> submitTaskStreamWithUsage(LLMRequest request, Consumer<String> onToken, Consumer<String> onThinking, Consumer<LLMStreamFinish> onFinish, List<LLMPromptResultPayload.RagHitPayload> ragHitsSink) {
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
                    safeTokenConsumer(onThinking),
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
        return ragCache;
    }

    public boolean hasCache(String uid) {
        return ragCache.hasCache(uid);
    }

    public void upsertRagEntry(String uid, String entryId, String content, float[] vector) {
        ragCache.upsert(uid, entryId, content, vector);
    }

    public void patchRagEntry(String uid, String entryId, String content, float[] vector, boolean updateContent, boolean updateVector) {
        ragCache.patch(uid, entryId, content, vector, updateContent, updateVector);
    }

    public void deleteRagEntry(String uid, String entryId) {
        ragCache.deleteEntry(uid, entryId);
    }

    public void clearRagUid(String uid) {
        ragCache.clearUid(uid);
    }

    public boolean hasRagUid(String uid) {
        return ragCache.hasCache(uid);
    }

    public boolean hasRagEntry(String uid, String entryId) {
        return ragCache.hasEntry(uid, entryId);
    }

    public List<RagCacheManager.RagEntrySearchResult> searchRagEntries(String uid, String queryText, int topK, float threshold) {
        return ragCache.searchEntries(uid, queryText, topK, threshold);
    }

    public RagLibraryRegistry.RagLibraryMeta registerRagLibrary(String uid, String modid, String visibility, List<String> tags) {
        ragLibraryRegistry.register(uid, modid, visibility, tags);
        return ragLibraryRegistry.meta(uid);
    }

    public void unregisterRagLibrary(String uid) {
        ragLibraryRegistry.unregister(uid);
    }

    public RagLibraryRegistry.RagLibraryMeta ragLibrary(String uid) {
        return ragLibraryRegistry.meta(uid);
    }

    public List<RagLibrarySearchResult> searchRagLibraryByUid(String uid, String queryText, int topK, float threshold) {
        List<RagCacheManager.RagEntrySearchResult> entries = searchRagEntries(uid, queryText, topK, threshold);
        if (entries.isEmpty()) {
            return List.of();
        }
        return List.of(new RagLibrarySearchResult(uid, ragLibraryRegistry.meta(uid), entries));
    }

    public List<RagLibrarySearchResult> searchSharedRagLibrariesByModid(String modid, String queryText, int topK, float threshold) {
        return searchLibraries(ragLibraryRegistry.sharedByModid(modid), queryText, topK, threshold);
    }

    public List<RagLibrarySearchResult> searchSharedRagLibrariesByTags(List<String> tags, String queryText, int topK, float threshold) {
        return searchLibraries(ragLibraryRegistry.sharedByTags(tags), queryText, topK, threshold);
    }

    private List<RagLibrarySearchResult> searchLibraries(List<RagLibraryRegistry.RagLibraryMeta> libraries, String queryText, int topK, float threshold) {
        if (libraries == null || libraries.isEmpty()) {
            return List.of();
        }
        List<RagLibrarySearchResult> results = new ArrayList<>();
        for (RagLibraryRegistry.RagLibraryMeta library : libraries) {
            if (library == null || library.uid().isBlank()) {
                continue;
            }
            List<RagCacheManager.RagEntrySearchResult> entries = searchRagEntries(library.uid(), queryText, topK, threshold);
            if (!entries.isEmpty()) {
                results.add(new RagLibrarySearchResult(library.uid(), library, entries));
            }
        }
        return results;
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

    public boolean supportsThinking() {
        return inferenceClient.supportsThinking();
    }

    public boolean supportsMtp() {
        return inferenceClient.supportsMtp();
    }

    public LlmMtpCapabilitySnapshot getMtpCapability() {
        return inferenceClient.getMtpCapability();
    }

    public LlmEngineCapabilitySnapshot getRuntimeCapabilities() {
        return inferenceClient.getRuntimeCapabilities();
    }

    public LlmContextBudgetSnapshot getContextBudgetPlan() {
        return inferenceClient.getContextBudgetPlan();
    }

    public LlmContextBudgetSnapshot getContextBudgetPlan(String lane) {
        return inferenceClient.getContextBudgetPlan(lane);
    }

    public CompletableFuture<LlmMtpCalibrationResult> calibrateMtpAsync(LlmMtpCalibrationRequest request) {
        return inferenceClient.calibrateMtpAsync(request);
    }

    public float[] embed(String text) throws Exception {
        return embeddingService.embed(text);
    }

    public float[][] embed(List<String> texts) throws Exception {
        return embeddingService.embed(texts);
    }

    public int getEmbeddingDimension() {
        return Math.max(0, embeddingService.getEmbeddingDimension());
    }

    public LLMPrimitiveResultPayload tokenCountResponse(String requestId, LLMRequest request) {
        try {
            PreparedResult prepared = prepareTokenCountRequest(request);
            return LLMPrimitiveResultPayload.tokenCount(requestId, inferenceClient.countChatPromptTokens(prepared.messages(), prepared.sampler()));
        } catch (Exception e) {
            return LLMPrimitiveResultPayload.failed(
                    requestId,
                    LLMPrimitiveQueryPayload.QUERY_TYPE_TOKEN_COUNT,
                    e instanceof UnsupportedOperationException ? "LLM_TOKEN_COUNT_UNSUPPORTED_INPUT" : "LLM_TOKEN_COUNT_FAILED",
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
                    results.add(LLMPrimitiveResultPayload.EmbedResultPayload.of(text, vector, includeVector, embeddingModelName(), embeddingNamespace()));
                }
            }
            if (!includeEmbeddingDetails) {
                results = results.stream()
                        .map(result -> new LLMPrimitiveResultPayload.EmbedResultPayload(result.text(), result.dimension(), new float[0], result.embeddingModelName(), result.embeddingNamespace()))
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
        boolean hasEmbedding = embeddingConfigured || embeddingDimension > 0;
        LlmMtpCapabilitySnapshot mtp = getMtpCapability();
        LlmEngineCapabilitySnapshot capabilities = getRuntimeCapabilities();
        LlmContextBudgetSnapshot budget = getContextBudgetPlan();
        int loadedContextSize = loadedContextSize(budget);
        int contextTokenBudget = contextTokenBudget(budget, loadedContextSize);
        String modelName = includeRuntimeDetails ? configName() : "";
        String modelProfile = includeRuntimeDetails ? modelProfile() : "";
        String embeddingModelName = includeRuntimeDetails ? embeddingModelName() : "";
        String embeddingNamespace = includeRuntimeDetails ? embeddingNamespace() : "";
        String failureMessage = includeRuntimeDetails ? "" : "";
        return new LLMRuntimeSnapshotPayload(
                ready,
                inferenceClient != null && inferenceClient.isReady(),
                hasEmbedding,
                embeddingDimension,
                capabilities.supportsThinking(),
                supportsMtp(),
                capabilities.supportsEmbeddedMtp(),
                capabilities.externalMtpAvailable(),
                mtp != null && mtp.supported() && mtp.calibrated(),
                mtp == null ? 0 : mtp.mtpLayerCount(),
                mtp == null ? 0 : mtp.recommendedDraftMax(),
                loadedContextSize,
                contextTokenBudget,
                hasChatQueueCapacity(),
                hasTaskQueueCapacity(),
                inferenceClient != null && inferenceClient.hasQueueCapacity(),
                inferenceClient != null ? inferenceClient.getChatQueueSize() : 0,
                inferenceClient != null ? inferenceClient.getTaskQueueSize() : 0,
                inferenceClient != null ? inferenceClient.getQueueSize() : 0,
                modelName,
                modelProfile,
                embeddingModelName,
                embeddingNamespace,
                failureMessage,
                System.currentTimeMillis()
        );
    }

    public void shutdown() {
        env.info("[LLMService] Shutdown complete");
    }

    private PreparedResult prepareRequest(LLMRequest request) {
        LLMRequest effectiveRequest = request != null ? request : new LLMRequest();
        MessageAssembler messages = new MessageAssembler();
        List<LLMPromptResultPayload.RagHitPayload> ragHits = new ArrayList<>();
        String lastUserMessage = extractLastUserMessage(effectiveRequest);

        for (Chunk chunk : effectiveRequest.getChunks()) {
            if (chunk == null || chunk.getType() == null) {
                continue;
            }
            if ("message".equalsIgnoreCase(chunk.getType())) {
                messages.appendMessages(chunk.getMessageContent());
            } else if ("rag".equalsIgnoreCase(chunk.getType())) {
                RagPreparation rag = processRagChunk(chunk, lastUserMessage);
                collectRagHits(chunk, rag.results(), ragHits);
                if (!rag.prompt().isEmpty()) {
                    messages.appendSystemPart(rag.prompt());
                }
            }
        }

        return new PreparedResult(
                buildLibsMessages(messages.finish()),
                createSampler(effectiveRequest),
                maxTokens(effectiveRequest),
                effectiveRequest.getTaskPriority(),
                effectiveRequest.getTaskPreemptible(),
                inferenceGovernor.resolve(effectiveRequest.getInferencePolicy(), effectiveRequest.isTaskLane(), supportsMtp())
                        .withRequestOptions(Boolean.TRUE.equals(effectiveRequest.getCaptureThinkingContent()), effectiveRequest.getToolsJson()),
                ragHits
        );
    }

    private PreparedResult prepareTokenCountRequest(LLMRequest request) {
        LLMRequest effectiveRequest = request != null ? request : new LLMRequest();
        MessageAssembler messages = new MessageAssembler();

        for (Chunk chunk : effectiveRequest.getChunks()) {
            if (chunk == null || chunk.getType() == null) {
                continue;
            }
            if ("message".equalsIgnoreCase(chunk.getType())) {
                messages.appendMessages(chunk.getMessageContent());
            } else if ("rag".equalsIgnoreCase(chunk.getType())) {
                throw new UnsupportedOperationException("TOKEN_COUNT only accepts message-only input; rag chunks may trigger retrieval or cache mutation");
            }
        }

        return new PreparedResult(
                buildLibsMessages(messages.finish()),
                createSampler(effectiveRequest),
                maxTokens(effectiveRequest),
                effectiveRequest.getTaskPriority(),
                effectiveRequest.getTaskPreemptible(),
                LlmInferenceOptions.defaults(),
                List.of()
        );
    }

    private RagPreparation processRagChunk(Chunk ragChunk, String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return RagPreparation.empty();
        }

        List<RagCacheManager.RagEntrySearchResult> results = searchRag(ragChunk, queryText);
        List<RagCacheManager.RagEntrySearchResult> budgeted = applyRagBudget(results, ragChunk.getMemoryRagTokenBudget());
        return new RagPreparation(budgeted, buildRagPrompt(budgeted, ragChunk.getPrompt()));
    }

    private List<RagCacheManager.RagEntrySearchResult> searchRag(Chunk ragChunk, String queryText) {
        String uid = ragChunk.getUid();
        boolean useCache = Boolean.TRUE.equals(ragChunk.getUseCache());
        List<String> contents = validTexts(ragChunk.getRagContent());

        if (useCache) {
            if (!contents.isEmpty()) {
                for (String content : contents) {
                    ragCache.upsert(uid, contentEntryId(content), content, null);
                }
            }
            return ragCache.searchEntries(uid, queryText, DEFAULT_RAG_TOP_K, DEFAULT_RAG_THRESHOLD);
        }

        if (contents.isEmpty()) {
            return List.of();
        }
        try {
            return inferenceClient.search(queryText, contents, DEFAULT_RAG_TOP_K, DEFAULT_RAG_THRESHOLD).stream()
                    .map(result -> new RagCacheManager.RagEntrySearchResult("", result.getContent(), result.getScore()))
                    .toList();
        } catch (Exception e) {
            env.error("[LLMService] Search via libs failed", e);
            return List.of();
        }
    }

    private List<RagCacheManager.RagEntrySearchResult> applyRagBudget(List<RagCacheManager.RagEntrySearchResult> results, Integer tokenBudget) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return results;
    }

    private void collectRagHits(Chunk chunk, List<RagCacheManager.RagEntrySearchResult> results, List<LLMPromptResultPayload.RagHitPayload> ragHits) {
        if (chunk == null || !Boolean.TRUE.equals(chunk.getIncludeRagHits()) || results == null || results.isEmpty()) {
            return;
        }

        List<LLMPromptResultPayload.HitEntry> entries = new ArrayList<>();
        for (RagCacheManager.RagEntrySearchResult r : results) {
            entries.add(LLMPromptResultPayload.HitEntry.of(r.entryId(), r.content(), r.score()));
        }
        ragHits.add(LLMPromptResultPayload.RagHitPayload.of(chunk.getUid(), entries));
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

    private String buildRagPrompt(List<RagCacheManager.RagEntrySearchResult> results, String prompt) {
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
            sb.append(i + 1).append(". ").append(results.get(i).content()).append('\n');
        }
        return sb.toString();
    }

    private List<ChatMessage> buildLibsMessages(List<MessageItem> orderedMessages) {
        return orderedMessages.stream()
                .map(m -> new ChatMessage(m.getRole(), m.getContent()))
                .toList();
    }

    private SamplerConfig createSampler(LLMRequest request) {
        SamplerConfig config = applyModelSamplingDefaults(SamplerConfig.defaults(), Boolean.TRUE.equals(request.getThinking()));
        Float temp = request.getTemperature();
        if (temp != null) {
            config.setTemperature(temp);
        }
        if (request.getTopK() != null) {
            config.setTopK(request.getTopK());
        }
        if (request.getTopP() != null) {
            config.setTopP(request.getTopP());
        }
        if (request.getMinP() != null) {
            config.setMinP(request.getMinP());
        }
        if (request.getPenaltyRepeat() != null) {
            config.setPenaltyRepeat(request.getPenaltyRepeat());
        }
        if (request.getPenaltyFreq() != null) {
            config.setPenaltyFreq(request.getPenaltyFreq());
        }
        if (request.getPenaltyPresent() != null) {
            config.setPenaltyPresent(request.getPenaltyPresent());
        }
        if (request.getPenaltyLastN() != null) {
            config.setPenaltyLastN(request.getPenaltyLastN());
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

    private String embeddingModelName() {
        return safeText(config.getLlmEmbeddingModelName());
    }

    private String embeddingNamespace() {
        String embedding = embeddingModelName();
        if (!cacheNamespace.isBlank() && !"default".equals(cacheNamespace)) {
            return cacheNamespace;
        }
        String llm = configName();
        return stableSegment(llm) + ":" + stableSegment(embedding);
    }

    private SamplerConfig applyModelSamplingDefaults(SamplerConfig sampler, boolean thinking) {
        LlmModelInfo info = LlmModelManager.getModelByName(configName());
        LlmModelInfo.SamplingSettings settings = info == null ? null : info.getSamplingSettings(thinking);
        if (settings == null || settings.isEmpty()) {
            return sampler;
        }
        if (settings.temperature != null) sampler.setTemperature(settings.temperature);
        if (settings.topK != null) sampler.setTopK(settings.topK);
        if (settings.topP != null) sampler.setTopP(settings.topP);
        if (settings.minP != null) sampler.setMinP(settings.minP);
        if (settings.penaltyRepeat != null) sampler.setPenaltyRepeat(settings.penaltyRepeat);
        if (settings.penaltyFreq != null) sampler.setPenaltyFreq(settings.penaltyFreq);
        if (settings.penaltyPresent != null) sampler.setPenaltyPresent(settings.penaltyPresent);
        if (settings.penaltyLastN != null) sampler.setPenaltyLastN(settings.penaltyLastN);
        return sampler;
    }

    private int loadedContextSize(LlmContextBudgetSnapshot budget) {
        int planned = budget == null ? 0 : budget.plannedContextSize();
        if (planned > 0) {
            return planned;
        }
        return Math.max(0, config.getLlmContextSize());
    }

    private int contextTokenBudget(LlmContextBudgetSnapshot budget, int loadedContextSize) {
        int plannedBudget = budget == null ? 0 : budget.promptTokenBudget();
        if (plannedBudget > 0) {
            return plannedBudget;
        }
        int margin = budget == null ? 64 : Math.max(0, budget.promptMarginTokens());
        return Math.max(0, loadedContextSize - margin);
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stableSegment(String value) {
        String normalized = value == null || value.isBlank() ? "default" : value.trim();
        return Integer.toHexString(java.util.Objects.hash(normalized));
    }

    private static String contentEntryId(String content) {
        return "content:" + Integer.toHexString(java.util.Objects.hash(content == null ? "" : content));
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

    private static LLMStreamFinish toStreamFinish(LlmStreamFinish finish) {
        if (finish == null) {
            return LLMStreamFinish.completed(LLMPromptResultPayload.TokenUsagePayload.empty());
        }
        String type = finish.type() == null ? "COMPLETED" : finish.type().name();
        return new LLMStreamFinish(type, toUsagePayload(finish.usage()), finish.error(), finish.thinkingContent());
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

    private record RagPreparation(List<RagCacheManager.RagEntrySearchResult> results, String prompt) {
        private RagPreparation {
            results = results != null ? List.copyOf(results) : List.of();
            prompt = prompt != null ? prompt : "";
        }

        static RagPreparation empty() {
            return new RagPreparation(List.of(), "");
        }
    }

    private static final class MessageAssembler {
        private final StringBuilder leadingSystem = new StringBuilder();
        private final List<MessageItem> messages = new ArrayList<>();
        private boolean dialogueStarted;

        void appendMessages(List<MessageItem> items) {
            if (items == null || items.isEmpty()) {
                return;
            }
            for (MessageItem item : items) {
                appendMessage(item);
            }
        }

        void appendSystemPart(String content) {
            if (content == null || content.isBlank()) {
                return;
            }
            if (leadingSystem.length() > 0) {
                leadingSystem.append('\n');
            }
            leadingSystem.append(content.trim());
        }

        List<MessageItem> finish() {
            List<MessageItem> result = new ArrayList<>();
            if (leadingSystem.length() > 0) {
                result.add(MessageItem.system(leadingSystem.toString()));
            }
            result.addAll(messages);
            return result;
        }

        private void appendMessage(MessageItem message) {
            if (message == null || message.getContent() == null || message.getContent().isBlank()) {
                return;
            }
            String role = normalizeRole(message.getRole());
            String content = message.getContent();
            if ("system".equals(role)) {
                if (dialogueStarted) {
                    throw new IllegalArgumentException("LLM_UNSUPPORTED_SYSTEM_POSITION");
                }
                appendSystemPart(content);
                return;
            }
            dialogueStarted = true;
            messages.add(new MessageItem(role, content));
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

    public record LLMResult(String text, String thinkingContent, List<LLMPromptResultPayload.RagHitPayload> ragHits, LLMPromptResultPayload.TokenUsagePayload usage) {
        public LLMResult {
            text = text != null ? text : "";
            thinkingContent = thinkingContent != null ? thinkingContent : "";
            ragHits = ragHits != null ? List.copyOf(ragHits) : List.of();
            usage = usage == null ? LLMPromptResultPayload.TokenUsagePayload.empty() : usage;
        }

        public LLMResult(String text, List<LLMPromptResultPayload.RagHitPayload> ragHits, LLMPromptResultPayload.TokenUsagePayload usage) {
            this(text, "", ragHits, usage);
        }
    }

    public record LLMStreamResult(String text, LLMStreamFinish finish) {
        public LLMStreamResult {
            text = text != null ? text : "";
            finish = finish == null ? LLMStreamFinish.completed(LLMPromptResultPayload.TokenUsagePayload.empty()) : finish;
        }
    }

    public record LLMStreamFinish(String type, LLMPromptResultPayload.TokenUsagePayload usage, Throwable error, String thinkingContent) {
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
            entries = entries != null ? List.copyOf(entries) : List.of();
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
        private String cacheNamespace = "default";
        private boolean embeddingConfigured;

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

        public Builder cacheNamespace(String cacheNamespace) {
            this.cacheNamespace = cacheNamespace;
            return this;
        }

        public Builder embeddingConfigured(boolean embeddingConfigured) {
            this.embeddingConfigured = embeddingConfigured;
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
