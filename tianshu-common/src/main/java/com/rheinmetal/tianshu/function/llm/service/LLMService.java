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

    private final IGameEnvironment env;
    private final JavaLlamaServer aiService;
    private final RagCacheManager ragCache;
    private volatile boolean initialized = false;

    private LLMService(Builder builder) {
        this.env = Objects.requireNonNull(builder.env, "env");
        this.aiService = Objects.requireNonNull(builder.aiService, "aiService");

        EmbeddingService embeddingAdapter = new LibsEmbeddingAdapter(aiService);

        if (builder.usePersistentCache) {
            this.ragCache = new PersistentRagCacheManager(env, embeddingAdapter, builder.cacheDirectory);
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
        List<ChatMessage> messages = buildLibsMessages(prepared.orderedMessages);
        SamplerConfig sampler = createSampler(request);

        try {
            String text = aiService.chat(messages, sampler, request.getMaxTokens());
            return new LLMResult(text, prepared.ragHits);
        } catch (Exception e) {
            env.error("[LLMService] Chat failed", e);
            throw new RuntimeException("LLM chat failed: " + e.getMessage(), e);
        }
    }

    public void chatStream(LLMRequest request, Consumer<String> onToken) {
        chatStream(request, onToken, null);
    }

    public void chatStream(LLMRequest request, Consumer<String> onToken, List<LLMPromptResultPayload.RagHitPayload> ragHitsSink) {
        PreparedResult prepared = prepareRequest(request);
        List<ChatMessage> messages = buildLibsMessages(prepared.orderedMessages);
        SamplerConfig sampler = createSampler(request);

        if (ragHitsSink != null) {
            ragHitsSink.addAll(prepared.ragHits);
        }

        try {
            aiService.chatStream(messages, sampler, onToken);
        } catch (Exception e) {
            env.error("[LLMService] Stream chat failed", e);
            throw new RuntimeException("LLM stream chat failed: " + e.getMessage(), e);
        }
    }

    public CompletableFuture<String> submitTask(LLMRequest request) {
        PreparedResult prepared = prepareRequest(request);
        List<ChatMessage> messages = buildLibsMessages(prepared.orderedMessages);
        SamplerConfig sampler = createSampler(request);

        return aiService.task(messages, sampler, request.getMaxTokens(),
                request.getTaskPriority(), request.getTaskPreemptible());
    }

    public void submitTaskStream(LLMRequest request, Consumer<String> onToken, List<LLMPromptResultPayload.RagHitPayload> ragHitsSink) {
        PreparedResult prepared = prepareRequest(request);
        List<ChatMessage> messages = buildLibsMessages(prepared.orderedMessages);
        SamplerConfig sampler = createSampler(request);

        if (ragHitsSink != null) {
            ragHitsSink.addAll(prepared.ragHits);
        }

        aiService.taskStream(messages, sampler, request.getMaxTokens(),
                request.getTaskPriority(), request.getTaskPreemptible(), onToken);
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
        return initialized && aiService.isReady();
    }

    public boolean hasChatQueueCapacity() {
        return aiService.hasChatQueueCapacity();
    }

    public boolean hasTaskQueueCapacity() {
        return aiService.hasTaskQueueCapacity();
    }

    public void shutdown() {
        ragCache.clear();
        env.info("[LLMService] Shutdown complete");
    }

    private PreparedResult prepareRequest(LLMRequest request) {
        if (request == null) {
            return new PreparedResult(List.of(), List.of());
        }

        List<MessageItem> orderedMessages = new ArrayList<>();
        List<LLMPromptResultPayload.RagHitPayload> ragHits = new ArrayList<>();

        String lastUserMessage = extractLastUserMessage(request);

        for (Chunk chunk : request.getChunks()) {
            if ("message".equalsIgnoreCase(chunk.getType())) {
                if (chunk.getMessageContent() != null) {
                    orderedMessages.addAll(chunk.getMessageContent());
                }
            } else if ("rag".equalsIgnoreCase(chunk.getType())) {
                List<RagSearchResult> results = processRagChunk(chunk, lastUserMessage);
                collectRagHits(chunk.getUid(), results, chunk.getIncludeRagHits(), ragHits);
                String ragSystemContent = buildRagPrompt(results, chunk.getPrompt());
                if (!ragSystemContent.isEmpty()) {
                    orderedMessages.add(MessageItem.system(ragSystemContent));
                }
            }
        }

        return new PreparedResult(orderedMessages, ragHits);
    }

    private List<RagSearchResult> processRagChunk(Chunk ragChunk, String queryText) {
        String uid = ragChunk.getUid();
        boolean useCache = Boolean.TRUE.equals(ragChunk.getUseCache());
        List<String> contents = ragChunk.getRagContent();

        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }

        if (useCache) {
            if (contents != null && !contents.isEmpty()) {
                ragCache.index(uid, contents);
            }
            return ragCache.search(uid, queryText, DEFAULT_RAG_TOP_K, DEFAULT_RAG_THRESHOLD);
        } else {
            try {
                return aiService.search(queryText, contents, DEFAULT_RAG_TOP_K, DEFAULT_RAG_THRESHOLD);
            } catch (Exception e) {
                env.error("[LLMService] Search via libs failed", e);
                return List.of();
            }
        }
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
            if ("message".equalsIgnoreCase(chunk.getType()) && chunk.getMessageContent() != null) {
                for (int j = chunk.getMessageContent().size() - 1; j >= 0; j--) {
                    MessageItem msg = chunk.getMessageContent().get(j);
                    if ("user".equalsIgnoreCase(msg.getRole()) && msg.getContent() != null && !msg.getContent().isBlank()) {
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
        boolean hasPrompt = prompt != null && !prompt.isBlank();

        if (hasPrompt) {
            sb.append(prompt).append("\n");
        }

        for (int i = 0; i < results.size(); i++) {
            sb.append(i + 1).append(". ").append(results.get(i).getContent()).append("\n");
        }

        return sb.toString();
    }

    private List<ChatMessage> buildLibsMessages(List<MessageItem> orderedMessages) {
        return orderedMessages.stream()
                .map(m -> new ChatMessage(m.getRole(), m.getContent()))
                .toList();
    }

    private SamplerConfig createSampler(LLMRequest request) {
        SamplerConfig config = new SamplerConfig();

        Float temp = request.getTemperature();
        if (temp != null) {
            config.setTemperature(temp);
        }

        Boolean thinking = request.getThinking();
        if (Boolean.TRUE.equals(thinking)) {
            config.setEnableThinking(true);
        }

        return config;
    }

    private record PreparedResult(
            List<MessageItem> orderedMessages,
            List<LLMPromptResultPayload.RagHitPayload> ragHits
    ) {}

    private static final class LibsEmbeddingAdapter implements EmbeddingService {
        private final JavaLlamaServer aiService;
        private volatile int cachedDimension = -1;

        LibsEmbeddingAdapter(JavaLlamaServer aiService) {
            this.aiService = aiService;
        }

        @Override
        public float[] embed(String text) throws Exception {
            float[] result = aiService.embed(text);
            if (cachedDimension < 0 && result != null && result.length > 0) {
                cachedDimension = result.length;
            }
            return result;
        }

        @Override
        public float[][] embed(List<String> texts) throws Exception {
            float[][] result = aiService.embed(texts);
            if (cachedDimension < 0 && result != null && result.length > 0 && result[0] != null) {
                cachedDimension = result[0].length;
            }
            return result;
        }

        @Override
        public int getEmbeddingDimension() {
            return cachedDimension;
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
        private JavaLlamaServer aiService;
        private boolean usePersistentCache = true;
        private java.nio.file.Path cacheDirectory;

        public Builder env(IGameEnvironment env) {
            this.env = env;
            return this;
        }

        public Builder aiService(JavaLlamaServer aiService) {
            this.aiService = aiService;
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

        public LLMService build() {
            Objects.requireNonNull(env, "env must be set");
            Objects.requireNonNull(aiService, "aiService must be set");
            return new LLMService(this);
        }
    }
}
