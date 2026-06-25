package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
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
    void ragPromptAndHitsRespectTokenBudget() {
        FakeInferenceClient client = new FakeInferenceClient();
        LLMService service = service(client);
        LLMRequest request = LLMRequest.of(
                Chunk.message(MessageItem.user("where")),
                Chunk.rag("memory", "RAG:", List.of("abcdefghijklmnop"), true, true, 1)
        );

        LLMService.LLMResult result = service.chat(request);

        assertTrue(client.lastMessages.stream().anyMatch(message ->
                "system".equals(message.role) && message.content.contains("[上下文已按预算截断]")));
        assertEquals(1, result.ragHits().size());
        assertTrue(result.ragHits().get(0).hits().get(0).content().contains("[上下文已按预算截断]"));
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
    void ragHitsKeepUidAndScopeForMultipleRagChunks() {
        FakeInferenceClient client = new FakeInferenceClient();
        LLMService service = service(client);
        LLMRequest request = LLMRequest.of(
                Chunk.message(MessageItem.user("query text")),
                Chunk.rag("world-memory", "World:", List.of("world hit"), false, true, 1000),
                Chunk.globalRag("global-lore", "Global:", List.of("global hit"), false, true, 1000)
        );

        LLMService.LLMResult result = service.chat(request);

        assertEquals(2, result.ragHits().size());
        assertEquals("world-memory", result.ragHits().get(0).uid());
        assertEquals(false, result.ragHits().get(0).globalRagCache());
        assertEquals("global-lore", result.ragHits().get(1).uid());
        assertEquals(true, result.ragHits().get(1).globalRagCache());
    }

    @Test
    void globalRagCacheIsSeparateFromWorldRagCacheForSameUid() {
        FakeInferenceClient client = new FakeInferenceClient();
        LLMService service = service(client);
        service.indexCache("shared", List.of("global memory"), true);

        service.chat(LLMRequest.of(
                Chunk.message(MessageItem.user("query text")),
                Chunk.rag("shared", "RAG:", List.of(), true, true, 1000)
        ));

        assertFalse(client.lastMessages.stream().anyMatch(message ->
                "system".equals(message.role) && message.content.contains("global memory")));

        service.chat(LLMRequest.of(
                Chunk.message(MessageItem.user("query text")),
                Chunk.globalRag("shared", "RAG:", List.of(), true, true, 1000)
        ));

        assertTrue(client.lastMessages.stream().anyMatch(message ->
                "system".equals(message.role) && message.content.contains("global memory")));
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
        assertFalse(service.supportsEnableThinking());
    }

    private static LLMService service(FakeInferenceClient client) {
        return LLMService.builder()
                .env(new FakeGameEnvironment())
                .inferenceClient(client)
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
        private String lastSearchQuery;
        private List<String> lastSearchTexts = List.of();
        private boolean ready = true;
        private boolean chatQueueCapacity = true;
        private boolean taskQueueCapacity = true;
        private boolean thinkingSupported = true;
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
        public void chatStream(List<ChatMessage> messages, SamplerConfig sampler, Consumer<String> onToken) {
            lastMessages = List.copyOf(messages);
            lastSampler = sampler;
            onToken.accept("ok");
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
        public float[] embed(String text) {
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

        @Override public boolean isReady() { return ready; }
        @Override public boolean hasChatQueueCapacity() { return chatQueueCapacity; }
        @Override public boolean hasTaskQueueCapacity() { return taskQueueCapacity; }
        @Override public boolean supportsEnableThinking() { return thinkingSupported; }
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
    }
}
