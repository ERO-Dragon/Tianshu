package com.rheinmetal.tianshu.function.weaving;

import com.rheinmetal.tianshu.function.weaving.contract.LLMCacheManagePayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMCacheManageResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.function.weaving.protocol.LlmCapabilities;
import com.rheinmetal.tianshu.function.weaving.service.EmbeddingRuntime;
import com.rheinmetal.tianshu.function.weaving.service.LLMService;
import com.rheinmetal.tianshu.function.weaving.service.LlmInferenceClient;
import com.rheinmetal.tianshu.function.weaving.service.LlmPromptFactory;
import com.rheinmetal.tianshu.function.weaving.service.LlmRagStorageService;
import com.rheinmetal.tianshu.function.weaving.service.RagPersistenceScheduler;
import com.rheinmetal.tianshu.function.weaving.usage.LlmPromptCacheUsage;
import com.rheinmetal.tianshu.function.weaving.usage.LlmUsageLedger;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.LlmGenerationResult;
import com.rheinmetal.tianshu.libs.llm.LlmStreamFinish;
import com.rheinmetal.tianshu.libs.llm.LlmTokenUsage;
import com.rheinmetal.tianshu.libs.llm.StreamFinishType;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The protocol face of the lane: request execution, streaming, cache management, primitive queries
 * and the failure paths. No lane, no task admission, no status topic — those concepts are gone.
 */
class LlmProtocolAdapterTest {
    @TempDir
    Path tempDir;

    @Test
    void blockingRequestCompletesWithTheProviderAnswer() {
        StubInferenceClient client = new StubInferenceClient();
        client.text = "answer";
        client.thinking = "reasoning";
        LLMService service = service(client, new EmbeddingRuntime());
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = adapter(runtime, service, new LlmUsageLedger());
        RecordingContext context = new RecordingContext();
        TianshuEnvelope envelope = requestEnvelope(payload("req-1", false, "hello"));

        adapter.handleLLMRequest(envelope, context);

        assertEquals(1, context.completed.get());
        assertEquals(0, context.failed.get());
        assertEquals(1, client.chatCalls);
        assertEquals(0, client.streamCalls);
    }

    @Test
    void streamingRequestPublishesChunksAndOneTerminalFrame() {
        StubInferenceClient client = new StubInferenceClient();
        client.tokens = List.of("Hel", "lo");
        client.text = "Hello";
        client.thinking = "reasoning";
        LLMService service = service(client, new EmbeddingRuntime());
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = adapter(runtime, service, new LlmUsageLedger());
        RecordingContext context = new RecordingContext();
        TianshuEnvelope envelope = requestEnvelope(new LLMPromptRequestPayload(
                "req-stream",
                List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello")),
                true,
                true,
                0,
                LLMPromptRequestPayload.SamplingPayload.empty()
        ));
        List<LLMPromptStreamChunkPayload> chunks = captureChunks(runtime, envelope.envelopeId());

        adapter.handleLLMRequest(envelope, context);

        assertEquals(1, context.completed.get());
        assertEquals(1, client.streamCalls);
        assertTrue(await(() -> chunks.size() == 3), "one thinking frame, one batched text frame and one terminal frame");
        // Frames are published in order, but the runtime dispatches responses on a broker, so they can
        // reach a handler on different workers. The index is the ordering contract; a caller that
        // appends by arrival order would corrupt the answer.
        List<LLMPromptStreamChunkPayload> ordered = chunks.stream()
                .sorted(java.util.Comparator.comparingInt(LLMPromptStreamChunkPayload::index))
                .toList();
        assertEquals("reasoning", ordered.get(0).thinkingContent());
        assertEquals("Hello", ordered.get(1).text(), "the token burst is coalesced into one chunk");
        assertTrue(ordered.get(2).finished());
        assertEquals("COMPLETED", ordered.get(2).finishType());
        assertEquals("reasoning", ordered.get(2).thinkingContent());
        assertEquals(2, ordered.get(2).index());
    }

    @Test
    void emptyMessagesFailBeforeTheProviderIsCalled() {
        StubInferenceClient client = new StubInferenceClient();
        LLMService service = service(client, new EmbeddingRuntime());
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = adapter(runtime, service, new LlmUsageLedger());
        RecordingContext context = new RecordingContext();
        TianshuEnvelope envelope = requestEnvelope(new LLMPromptRequestPayload(
                "req-empty", List.of(), false, false, 0, LLMPromptRequestPayload.SamplingPayload.empty()
        ));
        List<LLMPromptResultPayload> results = captureResults(runtime, envelope.envelopeId());

        adapter.handleLLMRequest(envelope, context);

        assertEquals(1, context.failed.get());
        assertEquals(0, client.chatCalls + client.streamCalls);
        assertTrue(await(() -> results.size() == 1));
        assertEquals("FAILED", results.get(0).status());
        // A refusal keeps its own code: it used to arrive as LLM_REQUEST_FAILED with the reason only in
        // the message, which made the caller parse text to learn what it got wrong.
        assertEquals("LLM_EMPTY_MESSAGES", results.get(0).errorCode());
    }

    @Test
    void aRequestWithoutAServiceIsRejected() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = adapter(runtime, null, new LlmUsageLedger());
        RecordingContext context = new RecordingContext();
        TianshuEnvelope envelope = requestEnvelope(payload("req-1", false, "hello"));
        List<LLMPromptResultPayload> results = captureResults(runtime, envelope.envelopeId());

        adapter.handleLLMRequest(envelope, context);

        assertEquals(1, context.failed.get());
        assertTrue(await(() -> results.size() == 1));
        assertEquals("FAILED", results.get(0).status());
        assertEquals("LLM_SERVICE_NOT_READY", results.get(0).errorCode());
    }

    @Test
    void cacheManageWritesAndSearchesThroughTheStorageService() {
        EmbeddingRuntime embedding = new EmbeddingRuntime();
        LlmRagStorageService storage = storage(embedding);
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = adapter(runtime, null, new LlmUsageLedger());
        adapter.setRagStorageService(storage);
        RecordingContext context = new RecordingContext();

        adapter.handleLLMCacheManage(cacheEnvelope(LLMCacheManagePayload.upsertEntry(
                "memory", "entry-1", "diamond pickaxe in ender chest", new float[]{1f, 0f})), context);
        adapter.handleLLMCacheManage(cacheEnvelope(LLMCacheManagePayload.searchUid(
                "memory", "where is the pickaxe", 4, 0.0f)), context);

        assertTrue(storage.hasEntry("memory", "entry-1"));
        assertEquals(2, context.completed.get());
    }

    @Test
    void tokenCountQueryUsesTheEstimator() {
        StubInferenceClient client = new StubInferenceClient();
        client.tokenCount = 42;
        LLMService service = service(client, new EmbeddingRuntime());
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = adapter(runtime, service, new LlmUsageLedger());
        TianshuEnvelope envelope = primitiveEnvelope(LLMPrimitiveQueryPayload.tokenCount(
                "count-1", "hello", List.of()));
        List<LLMPrimitiveResultPayload> results = capturePrimitiveResults(runtime, envelope.envelopeId());

        adapter.handleLLMPrimitiveQuery(envelope, new RecordingContext());

        assertTrue(await(() -> results.size() == 1));
        assertEquals(42, results.get(0).tokenCount());
        assertEquals(1, client.countCalls);
    }

    @Test
    void embeddingQueryFailsLoudlyWhenTheEmbeddingLaneIsNotBound() {
        LLMService service = service(new StubInferenceClient(), new EmbeddingRuntime());
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = adapter(runtime, service, new LlmUsageLedger());
        TianshuEnvelope envelope = primitiveEnvelope(LLMPrimitiveQueryPayload.embed(
                "embed-1", List.of("hello"), true, true));
        List<LLMPrimitiveResultPayload> results = capturePrimitiveResults(runtime, envelope.envelopeId());

        adapter.handleLLMPrimitiveQuery(envelope, new RecordingContext());

        assertTrue(await(() -> results.size() == 1));
        assertEquals("FAILED", results.get(0).status());
        assertEquals("LLM_EMBEDDING_UNAVAILABLE", results.get(0).errorCode());
    }

    @Test
    void statusSnapshotReportsDeclaredCapacityAndPromptBudget() {
        LLMService service = service(new StubInferenceClient(), new EmbeddingRuntime());
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = adapter(runtime, service, new LlmUsageLedger());
        TianshuEnvelope envelope = primitiveEnvelope(LLMPrimitiveQueryPayload.status("status-1"));
        List<LLMPrimitiveResultPayload> results = capturePrimitiveResults(runtime, envelope.envelopeId());

        adapter.handleLLMPrimitiveQuery(envelope, new RecordingContext());

        assertTrue(await(() -> results.size() == 1));
        assertEquals(102_400, results.get(0).runtimeSnapshot().contextSize());
        assertTrue(results.get(0).runtimeSnapshot().contextTokenBudget() > 0);
        assertFalse(results.get(0).runtimeSnapshot().embeddingAvailable());
    }

    // --- fixtures -----------------------------------------------------------------------------

    private static boolean await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return condition.getAsBoolean();
            }
        }
        return condition.getAsBoolean();
    }

    private LlmProtocolAdapter adapter(ProtocolRuntime runtime, LLMService service, LlmUsageLedger ledger) {
        return new LlmProtocolAdapter(
                runtime,
                service,
                new LlmPromptFactory(() -> "deepseek-flash"),
                ledger
        );
    }

    private LLMService service(LlmInferenceClient client, EmbeddingRuntime embedding) {
        return LLMService.builder()
                .env(new FakeGameEnvironment())
                .config(new TestLlmSupport.FakeConfig(tempDir))
                .inferenceClient(client)
                .embeddingRuntime(embedding)
                .cacheNamespace("test:namespace")
                .build();
    }

    private LlmRagStorageService storage(EmbeddingRuntime embedding) {
        return new LlmRagStorageService(
                new FakeGameEnvironment(), tempDir, "test:namespace", true,
                Runnable::run, RagPersistenceScheduler.immediate(), embedding
        );
    }

    private static LLMPromptRequestPayload payload(String requestId, boolean stream, String... messages) {
        List<LLMPromptRequestPayload.MessageItemPayload> items = new ArrayList<>();
        for (String message : messages) {
            items.add(LLMPromptRequestPayload.MessageItemPayload.user(message));
        }
        return new LLMPromptRequestPayload(
                requestId, items, stream, false, 0, LLMPromptRequestPayload.SamplingPayload.empty()
        );
    }

    private static TianshuEnvelope requestEnvelope(LLMPromptRequestPayload payload) {
        return EnvelopeBuilder.requestCapability(
                "module.test",
                LlmCapabilities.LLM_REQUEST,
                PayloadType.of("LLM_PROMPT_REQUEST"),
                payload
        ).build();
    }

    private static TianshuEnvelope cacheEnvelope(LLMCacheManagePayload payload) {
        return EnvelopeBuilder.requestCapability(
                "module.test",
                LlmCapabilities.LLM_CACHE_MANAGE,
                PayloadType.of("LLM_CACHE_MANAGE"),
                payload
        ).build();
    }

    private static TianshuEnvelope primitiveEnvelope(LLMPrimitiveQueryPayload payload) {
        return EnvelopeBuilder.requestCapability(
                "module.test",
                LlmCapabilities.LLM_PRIMITIVE_QUERY,
                PayloadType.of("LLM_PRIMITIVE_QUERY"),
                payload
        ).build();
    }

    private static List<LLMPromptResultPayload> captureResults(ProtocolRuntime runtime, String requestEnvelopeId) {
        List<LLMPromptResultPayload> results = new CopyOnWriteArrayList<>();
        registerResponse(runtime, requestEnvelopeId, "LLM_PROMPT_RESULT", LLMPromptResultPayload.class,
                (envelope, context) -> {
                    if (envelope.payload() instanceof LLMPromptResultPayload payload) {
                        results.add(payload);
                    }
                    context.complete(envelope.envelopeId());
                });
        return results;
    }

    private static List<LLMPromptStreamChunkPayload> captureChunks(ProtocolRuntime runtime, String requestEnvelopeId) {
        List<LLMPromptStreamChunkPayload> chunks = new CopyOnWriteArrayList<>();
        registerResponse(runtime, requestEnvelopeId, "LLM_PROMPT_STREAM_CHUNK", LLMPromptStreamChunkPayload.class,
                (envelope, context) -> {
                    if (envelope.payload() instanceof LLMPromptStreamChunkPayload payload) {
                        chunks.add(payload);
                    }
                    context.complete(envelope.envelopeId());
                });
        return chunks;
    }

    private static List<LLMPrimitiveResultPayload> capturePrimitiveResults(
            ProtocolRuntime runtime,
            String requestEnvelopeId
    ) {
        List<LLMPrimitiveResultPayload> results = new CopyOnWriteArrayList<>();
        registerResponse(runtime, requestEnvelopeId, "LLM_PRIMITIVE_RESULT", LLMPrimitiveResultPayload.class,
                (envelope, context) -> {
                    if (envelope.payload() instanceof LLMPrimitiveResultPayload payload) {
                        results.add(payload);
                    }
                    context.complete(envelope.envelopeId());
                });
        return results;
    }

    private static void registerResponse(
            ProtocolRuntime runtime,
            String requestEnvelopeId,
            String payloadType,
            Class<? extends com.rheinmetal.tianshu.protocol.ITianshuPayload> payloadClass,
            com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler handler
    ) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        CapabilityDescriptor capability = new CapabilityDescriptor(
                "test.llm.response." + payloadType,
                PayloadType.of(payloadType),
                payloadClass,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE
        );
        ModuleDescriptor module = new ModuleDescriptor(
                "module.test.llm.response",
                List.of(),
                defaults.threadPolicy(),
                defaults.cancellationScope(),
                defaults.failurePolicy(),
                defaults.deliveryPolicy(),
                defaults.cancellable(),
                defaults.supportsStreaming(),
                defaults.maxConcurrency(),
                defaults.queueCapacity()
        );
        runtime.registerResponseHandler(requestEnvelopeId, module, capability, handler);
    }

    /** Minimal inference client: fixed answer, fixed usage, configurable tokens. */
    private static final class StubInferenceClient implements LlmInferenceClient {
        private String text = "answer";
        private String thinking = "";
        private List<String> tokens = List.of("answer");
        private LlmTokenUsage usage = new LlmTokenUsage(10, 4);
        private int tokenCount = 7;
        private LlmPromptCacheUsage cacheUsage = LlmPromptCacheUsage.unknown();
        private int chatCalls;
        private int streamCalls;
        private int countCalls;

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
            reportCache(cacheSink);
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
            reportCache(cacheSink);
            return CompletableFuture.completedFuture(
                    new LlmGenerationResult(text, usage, captureThinkingContent ? thinking : ""));
        }

        @Override
        public int countChatPromptTokens(List<ChatMessage> messages) {
            countCalls++;
            return tokenCount;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        private void reportCache(Consumer<LlmPromptCacheUsage> sink) {
            if (sink != null && cacheUsage.known()) {
                sink.accept(cacheUsage);
            }
        }
    }

    private static final class RecordingContext implements ProtocolContext {
        private final java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger cancelled = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public void submit(TianshuEnvelope envelope) {
        }

        @Override
        public void complete(String envelopeId) {
            completed.incrementAndGet();
        }

        @Override
        public void fail(String envelopeId, String reasonCode, String message, Throwable throwable) {
            failed.incrementAndGet();
        }

        @Override
        public void cancel(String envelopeId, String reasonCode, String message) {
            cancelled.incrementAndGet();
        }

        @Override
        public boolean isCancelled(String envelopeId) {
            return false;
        }

        @Override
        public void onCancel(String envelopeId, Consumer<TianshuEnvelope> callback) {
        }
    }
}
