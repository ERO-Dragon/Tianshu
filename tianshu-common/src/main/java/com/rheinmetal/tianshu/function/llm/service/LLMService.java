package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class LLMService {

    private static final int DEFAULT_RAG_TOP_K = 4;
    private static final float DEFAULT_RAG_THRESHOLD = 0.7f;
    private static final int DEFAULT_RAG_TOKEN_BUDGET = 1000;

    private final IGameEnvironment env;
    private final LlmInferenceClient inferenceClient;
    private final RagCacheManager ragCache;
    private volatile boolean initialized = false;

    private LLMService(Builder builder) {
        this.env = Objects.requireNonNull(builder.env, "env");
        this.inferenceClient = Objects.requireNonNull(builder.inferenceClient, "inferenceClient");

        EmbeddingService embeddingAdapter = new ClientEmbeddingAdapter(inferenceClient);
        if (builder.usePersistentCache) {
            this.ragCache = new PersistentRagCacheManager(env, embeddingAdapter, builder.cacheDirectory, builder.cacheNamespace);
        } else {
            this.ragCache = new DefaultRagCacheManager(env, embeddingAdapter);
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
            String text = inferenceClient.chat(prepared.messages(), prepared.sampler(), prepared.maxTokens());
            return new LLMResult(text, prepared.ragHits());
        } catch (Exception e) {
            env.error("[LLMService] Chat failed", e);
            throw new RuntimeException("LLM chat failed: " + safeMessage(e), e);
        }
    }

    public void chatStream(LLMRequest request, Consumer<String> onToken) {
        chatStream(request, onToken, null);
    }

    public void chatStream(LLMRequest request, Consumer<String> onToken, List<LLMPromptResultPayload.RagHitPayload> ragHitsSink) {
        PreparedResult prepared = prepareRequest(request);
        copyRagHits(prepared, ragHitsSink);
        try {
            inferenceClient.chatStream(prepared.messages(), prepared.sampler(), safeTokenConsumer(onToken));
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
                    prepared.taskPriority(), prepared.taskPreemptible());
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
                    prepared.taskPriority(), prepared.taskPreemptible(), safeTokenConsumer(onToken));
        } catch (Exception e) {
            env.error("[LLMService] Stream task submit failed", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public RagCacheManager getRagCache() {
        return ragCache;
    }

    public void evictCache(String uid) {
        ragCache.evict(uid);
    }

    public void evictCache(String uid, String content) {
        ragCache.evict(uid, content);
    }

    public boolean hasCache(String uid) {
        return ragCache.hasCache(uid);
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
                collectRagHits(chunk.getUid(), rag.results(), chunk.getIncludeRagHits(), ragHits);
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
                ragCache.index(uid, contents);
            }
            return ragCache.search(uid, queryText, DEFAULT_RAG_TOP_K, DEFAULT_RAG_THRESHOLD);
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
        int budget = tokenBudget != null && tokenBudget > 0 ? tokenBudget : DEFAULT_RAG_TOKEN_BUDGET;
        int used = 0;
        List<RagSearchResult> kept = new ArrayList<>();
        for (RagSearchResult result : results) {
            String content = result.getContent();
            int estimatedTokens = estimateTokens(content);
            if (estimatedTokens <= 0) {
                continue;
            }
            if (!kept.isEmpty() && used + estimatedTokens > budget) {
                break;
            }
            if (used + estimatedTokens <= budget) {
                kept.add(result);
                used += estimatedTokens;
            } else {
                String truncated = truncateToTokenBudget(content, Math.max(1, budget - used));
                if (!truncated.isBlank()) {
                    kept.add(new RagSearchResult(truncated, result.getScore()));
                }
                break;
            }
        }
        return kept;
    }

    private void collectRagHits(String uid, List<RagSearchResult> results, Boolean includeRagHits, List<LLMPromptResultPayload.RagHitPayload> ragHits) {
        if (!Boolean.TRUE.equals(includeRagHits) || results == null || results.isEmpty()) {
            return;
        }

        List<LLMPromptResultPayload.HitEntry> entries = new ArrayList<>();
        for (RagSearchResult r : results) {
            entries.add(LLMPromptResultPayload.HitEntry.of(r.getScore(), r.getContent()));
        }
        ragHits.add(LLMPromptResultPayload.RagHitPayload.of(uid, entries));
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

    private static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int ascii = 0;
        int nonAscii = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c <= 127) {
                ascii++;
            } else {
                nonAscii++;
            }
        }
        return Math.max(1, nonAscii + (ascii + 3) / 4);
    }

    private static String truncateToTokenBudget(String text, int budget) {
        if (text == null || text.isBlank() || budget <= 0) {
            return "";
        }
        int used = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int cost = Character.isWhitespace(c) ? 0 : (c <= 127 ? 1 : 4);
            int normalizedCost = c <= 127 ? cost : 4;
            int nextUsed = used + (normalizedCost == 0 ? 0 : normalizedCost);
            if ((nextUsed + 3) / 4 > budget) {
                break;
            }
            sb.append(c);
            used = nextUsed;
        }
        String truncated = sb.toString().trim();
        return truncated.length() < text.trim().length() ? truncated + "\n[上下文已按预算截断]" : truncated;
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
    }

    private record PreparedResult(
            List<ChatMessage> messages,
            SamplerConfig sampler,
            int maxTokens,
            int taskPriority,
            boolean taskPreemptible,
            List<LLMPromptResultPayload.RagHitPayload> ragHits
    ) {
        private PreparedResult {
            messages = messages != null ? List.copyOf(messages) : List.of();
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

    public record LLMResult(String text, List<LLMPromptResultPayload.RagHitPayload> ragHits) {
        public LLMResult {
            text = text != null ? text : "";
            ragHits = ragHits != null ? List.copyOf(ragHits) : List.of();
        }
    }

    public static class Builder {
        private IGameEnvironment env;
        private LlmInferenceClient inferenceClient;
        private boolean usePersistentCache = true;
        private java.nio.file.Path cacheDirectory;
        private String cacheNamespace = "default";

        public Builder env(IGameEnvironment env) {
            this.env = env;
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
