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
        private CompletableFuture<String> taskStreamFuture = CompletableFuture.completedFuture("streamed");
        private Consumer<String> taskStreamTokenConsumer = ignored -> {};

        @Override
        public String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) {
            lastMessages = List.copyOf(messages);
            lastSampler = sampler;
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
            return CompletableFuture.completedFuture("task");
        }

        @Override
        public CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, Consumer<String> onToken) {
            lastMessages = List.copyOf(messages);
            lastSampler = sampler;
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
            return texts.stream().map(text -> new RagSearchResult(text, 1.0)).toList();
        }

        @Override public boolean isReady() { return true; }
        @Override public boolean hasChatQueueCapacity() { return true; }
        @Override public boolean hasTaskQueueCapacity() { return true; }
        @Override public boolean supportsEnableThinking() { return true; }
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
