package com.rheinmetal.tianshu.function.weaving.service;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMRuntimeSnapshotPayload;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmContextBudget;
import com.rheinmetal.tianshu.function.weaving.usage.LlmPromptCacheUsage;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.LlmGenerationResult;
import com.rheinmetal.tianshu.libs.llm.LlmStreamFinish;
import com.rheinmetal.tianshu.libs.llm.LlmTokenUsage;
import com.rheinmetal.tianshu.libs.llm.StreamFinishType;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service is a transport: send a prompt, report the answer, answer the three measurement
 * queries. Nothing here assembles prompts or runs RAG.
 */
class LLMServiceTest {
    private static final String NAMESPACE = "test:namespace";

    @TempDir
    Path tempDir;

    @Test
    void chatForwardsThePromptAndReturnsTheProviderAnswer() {
        StubInferenceClient client = new StubInferenceClient();
        client.text = "answer";
        client.thinking = "reasoning";
        client.usage = new LlmTokenUsage(10, 4);
        LLMService service = service(client, new EmbeddingRuntime());

        LLMService.LLMResult result = service.chat(prompt("hello"), null);

        assertEquals("answer", result.text());
        assertEquals("reasoning", result.thinkingContent());
        assertEquals(10, result.usage().promptTokens());
        assertEquals(4, result.usage().completionTokens());
        assertEquals(List.of("user:hello"), client.lastMessages);
        assertTrue(client.lastCaptureThinking);
        assertEquals(1, client.lastReasoningTier);
    }

    @Test
    void streamForwardsVisibleAndThinkingTokens() {
        StubInferenceClient client = new StubInferenceClient();
        client.tokens = List.of("Hel", "lo");
        client.thinking = "why";
        LLMService service = service(client, new EmbeddingRuntime());
        List<String> tokens = new ArrayList<>();
        List<String> reasoning = new ArrayList<>();

        LLMService.LLMStreamResult result = service.chatStream(
                prompt("hello"), tokens::add, reasoning::add, null
        );

        assertEquals(List.of("Hel", "lo"), tokens);
        assertEquals(List.of("why"), reasoning);
        assertEquals("Hello", result.text());
        assertEquals("COMPLETED", result.finish().type());
        assertEquals("why", result.finish().thinkingContent());
    }

    @Test
    void embeddingQueryUsesTheBoundLocalEngine() {
        EmbeddingRuntime embedding = new EmbeddingRuntime();
        embedding.bind(new FixedEmbeddingService());
        LLMService service = service(new StubInferenceClient(), embedding);

        LLMPrimitiveResultPayload result = service.embedResponse(
                "embed-1", List.of("a", "b"), true, true
        );

        assertEquals(LLMPrimitiveResultPayload.STATUS_COMPLETED, result.status());
        assertEquals(2, result.embedResults().size());
        assertEquals(2, result.embedResults().get(0).dimension());
        assertEquals(NAMESPACE, result.embedResults().get(0).embeddingNamespace());
    }

    @Test
    void embeddingQueryFailsWithoutABoundEngine() {
        LLMService service = service(new StubInferenceClient(), new EmbeddingRuntime());

        LLMPrimitiveResultPayload result = service.embedResponse(
                "embed-1", List.of("a"), true, true
        );

        assertEquals(LLMPrimitiveResultPayload.STATUS_FAILED, result.status());
        assertEquals("LLM_EMBEDDING_UNAVAILABLE", result.errorCode());
    }

    /**
     * "Give me the vectors" and "tell me which model produced them" are two different questions; the
     * transport used to AND them, so a caller that only asked for vectors got an empty one back.
     */
    @Test
    void vectorAndDetailsFlagsAreIndependent() {
        EmbeddingRuntime embedding = new EmbeddingRuntime();
        embedding.bind(new FixedEmbeddingService());
        LLMService service = service(new StubInferenceClient(), embedding);

        LLMPrimitiveResultPayload vectorsOnly = service.embedResponse("embed-v", List.of("a"), true, false);
        assertEquals(2, vectorsOnly.embedResults().get(0).dimension());
        assertEquals("", vectorsOnly.embedResults().get(0).embeddingModelName());
        assertEquals("", vectorsOnly.embedResults().get(0).embeddingNamespace());

        LLMPrimitiveResultPayload detailsOnly = service.embedResponse("embed-d", List.of("a"), false, true);
        assertEquals(2, detailsOnly.embedResults().get(0).dimension(), "the dimension is not a detail flag");
        assertEquals(0, detailsOnly.embedResults().get(0).vector().length);
        assertEquals(NAMESPACE, detailsOnly.embedResults().get(0).embeddingNamespace());
    }

    @Test
    void tokenCountIsDelegatedToTheEstimator() {
        StubInferenceClient client = new StubInferenceClient();
        client.tokenCount = 42;
        LLMService service = service(client, new EmbeddingRuntime());

        LLMPrimitiveResultPayload result = service.tokenCountResponse(
                "count-1", List.of(new ChatMessage("user", "hello"))
        );

        assertEquals(42, result.tokenCount());
        assertEquals(1, client.countCalls);
    }

    @Test
    void snapshotReportsDeclaredCapacityBudgetAndEmbeddingFacts() {
        EmbeddingRuntime embedding = new EmbeddingRuntime();
        embedding.bind(new FixedEmbeddingService());
        LLMService service = service(new StubInferenceClient(), embedding);

        LLMRuntimeSnapshotPayload snapshot = service.runtimeSnapshot();

        assertTrue(snapshot.ready());
        assertTrue(snapshot.embeddingAvailable());
        assertEquals(2, snapshot.embeddingDimension());
        assertEquals(102_400, snapshot.contextSize());
        assertEquals(LlmContextBudget.promptTokenBudget(102_400), snapshot.contextTokenBudget());
        assertEquals(NAMESPACE, snapshot.embeddingNamespace());
        assertFalse(snapshot.embeddingModelName().isBlank());
    }

    @Test
    void snapshotWithoutAnEmbeddingLaneReportsUnavailable() {
        LLMService service = service(new StubInferenceClient(), new EmbeddingRuntime());

        LLMRuntimeSnapshotPayload snapshot = service.runtimeSnapshot();

        assertFalse(snapshot.embeddingAvailable());
        assertEquals(-1, snapshot.embeddingDimension());
    }

    @Test
    void aPromptIsRequired() {
        LLMService service = service(new StubInferenceClient(), new EmbeddingRuntime());

        assertThrows(IllegalArgumentException.class, () -> service.chat(null, null));
    }

    /**
     * A cancelled stream stays cancelled. It used to be wrapped in a generic runtime failure, which
     * left the request handler matching the word "cancel" in a message to tell the two apart.
     */
    @Test
    void cancellationKeepsItsTypeInsteadOfBecomingAGenericFailure() {
        StubInferenceClient client = new StubInferenceClient();
        client.streamFailure = new CancellationException("REMOTE_LLM_REQUEST_CANCELLED");
        LLMService service = service(client, new EmbeddingRuntime());

        assertThrows(
                CancellationException.class,
                () -> service.chatStream(prompt("hello"), token -> { }, token -> { }, null)
        );
    }

    private LLMService service(LlmInferenceClient client, EmbeddingRuntime embedding) {
        return LLMService.builder()
                .env(new FakeGameEnvironment())
                .config(new TestLlmSupport.FakeConfig(tempDir).embeddingModelName("bge-m3"))
                .inferenceClient(client)
                .embeddingRuntime(embedding)
                .cacheNamespace(NAMESPACE)
                .build();
    }

    private static LlmPrompt prompt(String userMessage) {
        return new LlmPrompt(
                List.of(new ChatMessage("user", userMessage)),
                LLMPromptRequestPayload.SamplingPayload.empty(),
                0,
                true,
                1
        );
    }

    private static final class FixedEmbeddingService implements EmbeddingService {
        @Override
        public float[] embed(String text) {
            return new float[]{1f, 0f};
        }

        @Override
        public float[][] embed(List<String> texts) {
            return texts.stream().map(ignored -> new float[]{1f, 0f}).toArray(float[][]::new);
        }

        @Override
        public int getEmbeddingDimension() {
            return 2;
        }
    }

    private static final class StubInferenceClient implements LlmInferenceClient {
        private String text = "answer";
        private String thinking = "";
        private List<String> tokens = List.of("answer");
        private LlmTokenUsage usage = new LlmTokenUsage(1, 1);
        private int tokenCount = 1;
        private int chatCalls;
        private int streamCalls;
        private int countCalls;
        private List<String> lastMessages = List.of();
        private boolean lastCaptureThinking;
        private Integer lastReasoningTier;
        private boolean ready = true;
        /** Failure the next streamed call reports instead of an answer. */
        private RuntimeException streamFailure;

        @Override
        public LlmGenerationResult chat(
                List<ChatMessage> messages,
                LLMPromptRequestPayload.SamplingPayload sampling,
                int maxTokens,
                boolean captureThinkingContent,
                Integer reasoningTier,
                Consumer<LlmPromptCacheUsage> cacheSink
        ) {
            chatCalls++;
            lastMessages = messages.stream().map(message -> message.role + ":" + message.content).toList();
            lastCaptureThinking = captureThinkingContent;
            lastReasoningTier = reasoningTier;
            return new LlmGenerationResult(text, usage, captureThinkingContent ? thinking : "");
        }

        @Override
        public CompletableFuture<LlmGenerationResult> chatStream(
                List<ChatMessage> messages,
                LLMPromptRequestPayload.SamplingPayload sampling,
                int maxTokens,
                boolean captureThinkingContent,
                Integer reasoningTier,
                Consumer<String> onToken,
                Consumer<String> onThinking,
                Consumer<LlmStreamFinish> onFinish,
                Consumer<LlmPromptCacheUsage> cacheSink
        ) {
            streamCalls++;
            if (streamFailure != null) {
                return CompletableFuture.failedFuture(streamFailure);
            }
            if (onThinking != null && captureThinkingContent && !thinking.isEmpty()) {
                onThinking.accept(thinking);
            }
            for (String token : tokens) {
                if (onToken != null) {
                    onToken.accept(token);
                }
            }
            if (onFinish != null) {
                onFinish.accept(new LlmStreamFinish(
                        StreamFinishType.COMPLETED, usage, null, captureThinkingContent ? thinking : ""));
            }
            return CompletableFuture.completedFuture(new LlmGenerationResult(
                    String.join("", tokens), usage, captureThinkingContent ? thinking : ""));
        }

        @Override
        public int countChatPromptTokens(List<ChatMessage> messages) {
            countCalls++;
            return tokenCount;
        }

        @Override
        public boolean isReady() {
            return ready;
        }
    }
}
