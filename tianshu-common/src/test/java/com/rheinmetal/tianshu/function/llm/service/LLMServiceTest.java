package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.llm.runtime.LlmContextBudgetSnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmEngineCapabilitySnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationRequest;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCapabilitySnapshot;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.LlmGenerationResult;
import com.rheinmetal.tianshu.libs.llm.LlmStreamFinish;
import com.rheinmetal.tianshu.libs.llm.LlmTokenUsage;
import com.rheinmetal.tianshu.libs.llm.StreamFinishType;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLMServiceTest {

    @Test
    void chatDisablesThinkingWhenRequestThinkingIsFalse() {
        FakeInferenceClient client = new FakeInferenceClient();
        LLMService service = service(client);
        LLMRequest request = LLMRequest.ofMessage(MessageItem.user("hello"));
        request.setThinking(false);

        service.chat(request);

        assertNotNull(client.lastSampler);
        assertEquals(Boolean.FALSE, client.lastSampler.getEnableThinking());
    }

    @Test
    void ragPromptAndHitsRemainWholeWhenTheyFitTokenBudget() {
        FakeInferenceClient client = new FakeInferenceClient();
        LLMService service = service(client);
        service.upsertRagEntry("memory", "entry-1", "abcdefghijklmnop", new float[]{1f, 0f});
        LLMRequest request = LLMRequest.of(
                Chunk.message(MessageItem.user("where")),
                Chunk.rag("memory", "RAG:", List.of("abcdefghijklmnop"), true, true, 1)
        );

        LLMService.LLMResult result = service.chat(request);

        assertTrue(client.lastMessages.stream().anyMatch(message ->
                "system".equals(message.role) && message.content.contains("abcdefghijklmnop")));
        assertEquals(1, result.ragHits().size());
        assertEquals("abcdefghijklmnop", result.ragHits().get(0).hits().get(0).content());
    }

    @Test
    void ragTokenBudgetKeepsOnlyWholeResultsThatFitTokenizerCount() {
        FakeInferenceClient client = new FakeInferenceClient();
        client.countMessageCharacters = true;
        LLMService service = service(client);

        LLMService.LLMResult result = service.chat(LLMRequest.of(
                Chunk.message(MessageItem.user("query")),
                Chunk.rag("memory", "", List.of("1234", "5678"), false, true, 4)
        ));

        assertEquals("1. 1234", client.lastMessages.get(0).content.trim());
        assertEquals(1, result.ragHits().get(0).hits().size());
        assertEquals("1234", result.ragHits().get(0).hits().get(0).content());
    }

    @Test
    void leadingSystemMessagesAreMergedIntoSingleInitialSystemMessage() {
        FakeInferenceClient client = new FakeInferenceClient();
        LLMService service = service(client);

        service.chat(LLMRequest.ofMessage(
                MessageItem.system("system one"),
                MessageItem.system("system two"),
                MessageItem.user("hello")
        ));

        assertEquals(2, client.lastMessages.size());
        assertEquals("system", client.lastMessages.get(0).role);
        assertTrue(client.lastMessages.get(0).content.contains("system one"));
        assertTrue(client.lastMessages.get(0).content.contains("system two"));
        assertEquals("user", client.lastMessages.get(1).role);
        assertEquals("hello", client.lastMessages.get(1).content);
    }

    @Test
    void systemMessageAfterDialogueStartsIsRejected() {
        FakeInferenceClient client = new FakeInferenceClient();
        LLMService service = service(client);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.chat(LLMRequest.ofMessage(
                MessageItem.system("system"),
                MessageItem.user("hello"),
                MessageItem.system("late system")
        )));

        assertEquals("LLM_UNSUPPORTED_SYSTEM_POSITION", error.getMessage());
    }

    @Test
    void submitTaskStreamReturnsUnderlyingFuture() {
        FakeInferenceClient client = new FakeInferenceClient();
        LLMService service = service(client);
        CompletableFuture<String> future = new CompletableFuture<>();
        client.taskStreamFuture = future;
        List<String> tokens = new ArrayList<>();

        CompletableFuture<String> returned = service.submitTaskStream(
                LLMRequest.ofMessage(MessageItem.user("hello")),
                tokens::add,
                new ArrayList<>()
        );

        assertFalse(returned.isDone());
        client.taskStreamTokenConsumer.accept("a");
        future.complete("answer");
        assertEquals("answer", returned.join());
        assertEquals(List.of("a"), tokens);
    }

    @Test
    void messagesAreNormalizedAndBlankMessagesAreSkipped() {
        FakeInferenceClient client = new FakeInferenceClient();
        LLMService service = service(client);

        service.chat(LLMRequest.ofMessage(
                MessageItem.of("tool", "use default role"),
                MessageItem.of(" ASSISTANT ", "assistant reply"),
                MessageItem.user("   "),
                MessageItem.system(null)
        ));

        assertEquals(2, client.lastMessages.size());
        assertEquals("user", client.lastMessages.get(0).role);
        assertEquals("use default role", client.lastMessages.get(0).content);
        assertEquals("assistant", client.lastMessages.get(1).role);
        assertEquals("assistant reply", client.lastMessages.get(1).content);
    }

    @Test
    void ragWithoutCacheUsesInferenceSearchDirectly() {
        FakeInferenceClient client = new FakeInferenceClient();
        LLMService service = service(client);
        LLMRequest request = LLMRequest.of(
                Chunk.message(MessageItem.user("query text")),
                Chunk.rag("dynamic", "RAG:", List.of("one", "one", " ", "two"), false, true, 1000)
        );

        LLMService.LLMResult result = service.chat(request);

        assertEquals(1, client.searchCalls);
        assertEquals("query text", client.lastSearchQuery);
        assertEquals(List.of("one", "two"), client.lastSearchTexts);
        assertEquals(1, result.ragHits().size());
        assertTrue(client.lastMessages.stream().anyMatch(message ->
                "system".equals(message.role) && message.content.contains("one")));
    }

    @Test
    void blankRagPromptAddsOnlyRetrievedContents() {
        FakeInferenceClient client = new FakeInferenceClient();
        LLMService service = service(client);

        service.chat(LLMRequest.of(
                Chunk.message(MessageItem.user("query text")),
                Chunk.rag("dynamic", "", List.of("first fact", "second fact"), false, false, 1000)
        ));

        assertEquals("1. first fact\n2. second fact", client.lastMessages.get(0).content.trim());
    }

    @Test
    void explicitRagPromptRemainsTheCallerOwnedPrefix() {
        FakeInferenceClient client = new FakeInferenceClient();
        LLMService service = service(client);

        service.chat(LLMRequest.of(
                Chunk.message(MessageItem.user("query text")),
                Chunk.rag("dynamic", "Caller prompt:", List.of("fact"), false, false, 1000)
        ));

        assertEquals("Caller prompt:\n1. fact", client.lastMessages.get(0).content.trim());
    }

    @Test
    void sharedTagSearchEmbedsQueryOnceAcrossMultipleLibraries() {
        FakeInferenceClient client = new FakeInferenceClient();
        LLMService service = service(client);
        service.registerRagLibrary("library-a", "moda", "SHARED", List.of("main"));
        service.registerRagLibrary("library-b", "modb", "SHARED", List.of("main"));
        service.upsertRagEntry("library-a", "entry-a", "diamond pickaxe in chest", new float[]{1f, 0f});
        service.upsertRagEntry("library-b", "entry-b", "diamond ore near cave", new float[]{1f, 0f});
        client.embedCalls = 0;

        List<LLMService.RagLibrarySearchResult> results = service.searchSharedRagLibrariesByTags(List.of("main"), "diamond", 2, 0.1f);

        assertEquals(1, client.embedCalls);
        assertEquals(2, results.size());
    }

    @Test
    void tokenCountRejectsRagChunksWithoutSearchOrCacheMutation() {
        FakeInferenceClient client = new FakeInferenceClient();
        LLMService service = service(client);
        LLMRequest request = LLMRequest.of(
                Chunk.message(MessageItem.user("query text")),
                Chunk.rag("dynamic", "RAG:", List.of("one"), false, true, 1000)
        );

        var result = service.tokenCountResponse("count-rag", request);

        assertEquals("FAILED", result.status());
        assertEquals("LLM_TOKEN_COUNT_UNSUPPORTED_INPUT", result.errorCode());
        assertEquals(0, client.searchCalls);
        assertEquals(0, client.tokenCountCalls);
    }

    @Test
    void tokenCountIsDisabledWhenGenerationBackendHasNoTokenizer() {
        FakeInferenceClient client = new FakeInferenceClient();
        client.tokenCountingSupported = false;
        LLMService service = service(client);

        var result = service.tokenCountResponse("count-unavailable", LLMRequest.ofMessage(MessageItem.user("hello")));

        assertEquals("FAILED", result.status());
        assertEquals("LLM_TOKENIZER_UNAVAILABLE", result.errorCode());
    }

    @Test
    void embedAndRuntimeExposeEmbeddingIdentity() {
        FakeInferenceClient client = new FakeInferenceClient();
        LLMService service = service(client);

        var embed = service.embedResponse("embed-id", List.of("memory"), false, true);
        var runtime = service.toRuntimeSnapshot(true);

        assertEquals("test-embedding", embed.embedResults().get(0).embeddingModelName());
        assertEquals("5c13d660:fb945ffb", embed.embedResults().get(0).embeddingNamespace());
        assertEquals("test-embedding", runtime.embeddingModelName());
        assertEquals("5c13d660:fb945ffb", runtime.embeddingNamespace());
        assertEquals(2, runtime.embeddingDimension());
        assertTrue(runtime.embeddingAvailable());
    }

    @Test
    void runtimeSnapshotKeepsEmbeddingAvailableSeparateFromChatReady() {
        FakeInferenceClient client = new FakeInferenceClient();
        client.ready = false;
        LLMService service = service(client, true);

        var runtime = service.toRuntimeSnapshot(true);

        assertFalse(runtime.modelLoaded());
        assertTrue(runtime.embeddingAvailable());
        assertEquals(0, runtime.embeddingDimension());
        assertEquals("test-embedding", runtime.embeddingModelName());
    }

    @Test
    void ragHitsKeepUidForMultipleRagChunks() {
        FakeInferenceClient client = new FakeInferenceClient();
        LLMService service = service(client);
        LLMRequest request = LLMRequest.of(
                Chunk.message(MessageItem.user("query text")),
                Chunk.rag("world-memory", "World:", List.of("world hit"), false, true, 1000),
                Chunk.rag("global-lore", "Global:", List.of("global hit"), false, true, 1000)
        );

        LLMService.LLMResult result = service.chat(request);

        assertEquals(2, result.ragHits().size());
        assertEquals("world-memory", result.ragHits().get(0).uid());
        assertEquals("global-lore", result.ragHits().get(1).uid());
    }

    @Test
    void submitTaskPassesTaskPriorityPreemptibleMaxTokensAndSampler() {
        FakeInferenceClient client = new FakeInferenceClient();
        LLMService service = service(client);
        LLMRequest request = LLMRequest.ofMessage(MessageItem.user("task"));
        request.setTaskPriority(42);
        request.setTaskPreemptible(true);
        request.setMaxTokens(64);
        request.setTemperature(0.2f);
        request.setThinking(true);

        String text = service.submitTask(request).join();

        assertEquals("task", text);
        assertEquals(42, client.lastTaskPriority);
        assertTrue(client.lastTaskPreemptible);
        assertEquals(64, client.lastMaxTokens);
        assertEquals(0.2f, client.lastSampler.getTemperature(), 0.0001f);
        assertEquals(Boolean.TRUE, client.lastSampler.getEnableThinking());
    }

    @Test
    void taskPriorityIsClampedToPublicRange() {
        LLMRequest request = LLMRequest.ofMessage(MessageItem.user("task"));

        request.setTaskPriority(-1);
        assertEquals(LLMRequest.MIN_TASK_PRIORITY, request.getTaskPriority());

        request.setTaskPriority(1001);
        assertEquals(LLMRequest.MAX_TASK_PRIORITY, request.getTaskPriority());
    }

    @Test
    void readinessAndQueueCapabilitiesDelegateToInferenceClient() {
        FakeInferenceClient client = new FakeInferenceClient();
        client.ready = false;
        client.chatQueueCapacity = false;
        client.taskQueueCapacity = true;
        client.thinkingSupported = false;
        LLMService service = service(client);

        assertFalse(service.isReady());
        assertFalse(service.hasChatQueueCapacity());
        assertTrue(service.hasTaskQueueCapacity());
        assertFalse(service.supportsThinking());
    }

    private static LLMService service(FakeInferenceClient client) {
        return service(client, false);
    }

    private static LLMService service(FakeInferenceClient client, boolean embeddingConfigured) {
        return LLMService.builder()
            .env(new FakeGameEnvironment())
                .config(new FakeConfig())
                .inferenceClient(client)
                .embeddingConfigured(embeddingConfigured)
                .usePersistentCache(false)
                .build();
    }

    private static final class FakeInferenceClient implements LlmInferenceClient {
        private List<ChatMessage> lastMessages = List.of();
        private SamplerConfig lastSampler;
        private int lastMaxTokens;
        private int lastTaskPriority;
        private boolean lastTaskPreemptible;
        private int searchCalls;
        private int tokenCountCalls;
        private int embedCalls;
        private String lastSearchQuery;
        private List<String> lastSearchTexts = List.of();
        private boolean ready = true;
        private boolean chatQueueCapacity = true;
        private boolean taskQueueCapacity = true;
        private boolean thinkingSupported = true;
        private boolean countMessageCharacters;
        private boolean tokenCountingSupported = true;
        private CompletableFuture<String> taskStreamFuture = CompletableFuture.completedFuture("streamed");
        private Consumer<String> taskStreamTokenConsumer = ignored -> {};

        @Override
        public String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) {
            lastMessages = List.copyOf(messages);
            lastSampler = sampler;
            lastMaxTokens = maxTokens;
            return "ok";
        }

        @Override
        public String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options) {
            return chat(messages, sampler, maxTokens);
        }

        @Override
        public LlmGenerationResult chatWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options) {
            return new LlmGenerationResult(chat(messages, sampler, maxTokens), new LlmTokenUsage(messages.size(), 1));
        }

        @Override
        public void chatStream(List<ChatMessage> messages, SamplerConfig sampler, Consumer<String> onToken) {
            lastMessages = List.copyOf(messages);
            lastSampler = sampler;
            onToken.accept("ok");
        }

        @Override
        public void chatStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options, Consumer<String> onToken) {
            chatStream(messages, sampler, onToken);
        }

        @Override
        public CompletableFuture<String> chatStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options, Consumer<String> onToken, Consumer<LlmStreamFinish> onFinish) {
            chatStream(messages, sampler, onToken);
            if (onFinish != null) {
                onFinish.accept(new LlmStreamFinish(StreamFinishType.COMPLETED, new LlmTokenUsage(messages.size(), 1), null));
            }
            return CompletableFuture.completedFuture("ok");
        }

        @Override
        public CompletableFuture<String> chatStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options, Consumer<String> onToken, Consumer<String> onThinking, Consumer<LlmStreamFinish> onFinish) {
            return chatStreamWithUsage(messages, sampler, maxTokens, options, onToken, onFinish);
        }

        @Override
        public CompletableFuture<String> task(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible) {
            lastMessages = List.copyOf(messages);
            lastSampler = sampler;
            lastMaxTokens = maxTokens;
            lastTaskPriority = priority;
            lastTaskPreemptible = preemptible;
            return CompletableFuture.completedFuture("task");
        }

        @Override
        public CompletableFuture<String> task(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options) {
            return task(messages, sampler, maxTokens, priority, preemptible);
        }

        @Override
        public CompletableFuture<LlmGenerationResult> taskWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options) {
            return task(messages, sampler, maxTokens, priority, preemptible)
                    .thenApply(text -> new LlmGenerationResult(text, new LlmTokenUsage(messages.size(), 1)));
        }

        @Override
        public CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, Consumer<String> onToken) {
            lastMessages = List.copyOf(messages);
            lastSampler = sampler;
            lastMaxTokens = maxTokens;
            lastTaskPriority = priority;
            lastTaskPreemptible = preemptible;
            taskStreamTokenConsumer = onToken;
            return taskStreamFuture;
        }

        @Override
        public CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options, Consumer<String> onToken) {
            return taskStream(messages, sampler, maxTokens, priority, preemptible, onToken);
        }

        @Override
        public CompletableFuture<LlmGenerationResult> taskStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options, Consumer<String> onToken, Consumer<LlmStreamFinish> onFinish) {
            taskStream(messages, sampler, maxTokens, priority, preemptible, onToken);
            return taskStreamFuture.thenApply(text -> {
                if (onFinish != null) {
                    onFinish.accept(new LlmStreamFinish(StreamFinishType.COMPLETED, new LlmTokenUsage(messages.size(), 1), null));
                }
                return new LlmGenerationResult(text, new LlmTokenUsage(messages.size(), 1));
            });
        }

        @Override
        public CompletableFuture<LlmGenerationResult> taskStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options, Consumer<String> onToken, Consumer<String> onThinking, Consumer<LlmStreamFinish> onFinish) {
            return taskStreamWithUsage(messages, sampler, maxTokens, priority, preemptible, options, onToken, onFinish);
        }

        @Override
        public float[] embed(String text) {
            embedCalls++;
            return new float[]{1f, 0f};
        }

        @Override
        public float[][] embed(List<String> texts) {
            float[][] vectors = new float[texts.size()][];
            for (int i = 0; i < texts.size(); i++) {
                vectors[i] = new float[]{1f, 0f};
            }
            return vectors;
        }

        @Override
        public List<RagSearchResult> search(String queryText, List<String> texts, int topK, float threshold) {
            searchCalls++;
            lastSearchQuery = queryText;
            lastSearchTexts = List.copyOf(texts);
            return texts.stream().map(text -> new RagSearchResult(text, 1.0)).toList();
        }

        @Override
        public int countChatPromptTokens(List<ChatMessage> messages, SamplerConfig sampler) {
            tokenCountCalls++;
            if (countMessageCharacters) {
                return messages == null ? 0 : messages.stream().mapToInt(message -> message.content.length()).sum();
            }
            return messages == null ? 0 : messages.size();
        }

        @Override
        public boolean supportsTokenCounting() {
            return tokenCountingSupported;
        }

        @Override public boolean isReady() { return ready; }
        @Override public boolean hasChatQueueCapacity() { return chatQueueCapacity; }
        @Override public boolean hasTaskQueueCapacity() { return taskQueueCapacity; }
        @Override public boolean hasQueueCapacity() { return chatQueueCapacity; }
        @Override public int getChatQueueSize() { return 0; }
        @Override public int getTaskQueueSize() { return 0; }
        @Override public int getQueueSize() { return 0; }
        @Override public boolean supportsThinking() { return thinkingSupported; }
        @Override public boolean supportsMtp() { return false; }
        @Override public LlmMtpCapabilitySnapshot getMtpCapability() { return LlmMtpCapabilitySnapshot.unsupported(); }
        @Override public LlmEngineCapabilitySnapshot getRuntimeCapabilities() { return new LlmEngineCapabilitySnapshot(ready, thinkingSupported, false, false, false, 0); }
        @Override public LlmContextBudgetSnapshot getContextBudgetPlan() { return new LlmContextBudgetSnapshot(4096, 4096, 4096, 4096, 3000, 64, 0L, true, ""); }
        @Override public LlmContextBudgetSnapshot getContextBudgetPlan(String lane) { return getContextBudgetPlan(); }
        @Override public CompletableFuture<LlmMtpCalibrationResult> calibrateMtpAsync(LlmMtpCalibrationRequest request) { return CompletableFuture.completedFuture(LlmMtpCalibrationResult.unsupported()); }
    }

    private static final class FakeGameEnvironment implements IGameEnvironment {
        @Override public void displayMessageToPlayer(String message) {}
        @Override public void executeOnMainThread(Runnable task) { task.run(); }
        @Override public Path getGameDirectory() { return Path.of("."); }
        @Override public boolean isClientSide() { return true; }
        @Override public void openFolder(Path dir) {}
        @Override public void info(String msg) {}
        @Override public void warn(String msg) {}
        @Override public void error(String msg, Throwable t) {}
        @Override public com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink diagnostics() { return com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink.NOOP; }
    }

    private static final class FakeConfig implements LlmConfiguration {
        @Override public boolean isLlmEnabled() { return true; }
        @Override public String getCustomLlmName() { return ""; }
        @Override public Path getLlmBasePath() { return Path.of("."); }
        @Override public String getLlmEmbeddingModelName() { return "test-embedding"; }
    }
}
