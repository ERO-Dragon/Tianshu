package com.rheinmetal.tianshu.function.weaving;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.protocol.LlmCapabilities;
import com.rheinmetal.tianshu.function.weaving.service.EmbeddingRuntime;
import com.rheinmetal.tianshu.function.weaving.service.LLMService;
import com.rheinmetal.tianshu.function.weaving.service.LlmInferenceClient;
import com.rheinmetal.tianshu.function.weaving.service.LlmPromptFactory;
import com.rheinmetal.tianshu.function.weaving.usage.LlmPromptCacheUsage;
import com.rheinmetal.tianshu.function.weaving.usage.LlmPricing;
import com.rheinmetal.tianshu.function.weaving.usage.LlmUsageLedger;
import com.rheinmetal.tianshu.function.weaving.usage.LlmUsageSnapshot;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.LlmGenerationResult;
import com.rheinmetal.tianshu.libs.llm.LlmStreamFinish;
import com.rheinmetal.tianshu.libs.llm.LlmTokenUsage;
import com.rheinmetal.tianshu.libs.llm.StreamFinishType;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A finished request reaches the usage ledger exactly once, with the envelope's module as the
 * attribution and the provider's cache split when it reported one.
 */
class LlmUsageWiringTest {
    private static final int PROMPT_TOKENS = 10;
    private static final int COMPLETION_TOKENS = 4;
    private static final int CACHE_HIT_TOKENS = 7;
    private static final int CACHE_MISS_TOKENS = 3;

    @TempDir
    Path tempDir;

    @Test
    void aBlockingRequestIsCountedOnceForTheModuleThatIssuedIt() {
        LlmUsageLedger ledger = new LlmUsageLedger();
        LlmProtocolAdapter adapter = adapter(ledger, new LlmPromptCacheUsage(CACHE_HIT_TOKENS, CACHE_MISS_TOKENS));

        adapter.handleLLMRequest(envelope("module.ax", "req-1", false), new RecordingContext());

        assertTrue(await(() -> ledger.snapshot().total().requests() == 1L));
        LlmUsageSnapshot snapshot = ledger.snapshot();
        assertEquals(1L, snapshot.module("module.ax").requests());
        assertEquals(PROMPT_TOKENS, snapshot.module("module.ax").inputTokens());
        assertEquals(CACHE_HIT_TOKENS, snapshot.module("module.ax").cacheHitInputTokens());
        assertEquals(CACHE_MISS_TOKENS, snapshot.module("module.ax").cacheMissInputTokens());
        assertEquals(COMPLETION_TOKENS, snapshot.module("module.ax").outputTokens());
        assertEquals(1L, snapshot.module("module.ax").cacheReportedRequests());
    }

    @Test
    void aStreamedRequestIsAlsoCountedOnce() {
        LlmUsageLedger ledger = new LlmUsageLedger();
        LlmProtocolAdapter adapter = adapter(ledger, new LlmPromptCacheUsage(CACHE_HIT_TOKENS, CACHE_MISS_TOKENS));

        adapter.handleLLMRequest(envelope("module.ax", "req-stream", true), new RecordingContext());

        assertTrue(await(() -> ledger.snapshot().total().requests() == 1L));
        assertEquals(1L, ledger.snapshot().total().requests());
        assertEquals(CACHE_HIT_TOKENS, ledger.snapshot().module("module.ax").cacheHitInputTokens());
    }

    @Test
    void eachRequestKeepsItsOwnModuleAttribution() {
        LlmUsageLedger ledger = new LlmUsageLedger();
        LlmProtocolAdapter adapter = adapter(ledger, new LlmPromptCacheUsage(CACHE_HIT_TOKENS, CACHE_MISS_TOKENS));

        adapter.handleLLMRequest(envelope("module.ax", "req-ax", false), new RecordingContext());
        adapter.handleLLMRequest(envelope("module.tts", "req-tts", false), new RecordingContext());

        assertTrue(await(() -> ledger.snapshot().total().requests() == 2L));
        LlmUsageSnapshot snapshot = ledger.snapshot();
        assertEquals(1L, snapshot.module("module.ax").requests());
        assertEquals(1L, snapshot.module("module.tts").requests());
        assertEquals(0L, snapshot.module("module.never-called").requests());
    }

    @Test
    void anUnreportedCacheSplitIsRecordedAsNoDataAndPricedAsUncached() {
        LlmUsageLedger ledger = new LlmUsageLedger();
        LlmProtocolAdapter adapter = adapter(ledger, LlmPromptCacheUsage.unknown());

        adapter.handleLLMRequest(envelope("module.ax", "req-unknown", false), new RecordingContext());

        assertTrue(await(() -> ledger.snapshot().total().requests() == 1L));
        LlmUsageSnapshot snapshot = ledger.snapshot();
        assertEquals(PROMPT_TOKENS, snapshot.module("module.ax").inputTokens());
        assertEquals(0L, snapshot.module("module.ax").cacheHitInputTokens());
        assertEquals(0L, snapshot.module("module.ax").cacheMissInputTokens(),
                "an unreported split must not be recorded as a measured 0% hit rate");
        assertEquals(0L, snapshot.module("module.ax").cacheReportedRequests());
        assertEquals(-1D, snapshot.module("module.ax").hitRatio());

        // A silent provider is still priced as a full miss: 10 prompt tokens at 2.00 CNY/M plus
        // 4 output tokens at 8.00 CNY/M.
        LlmPricing pricing = new LlmPricing(
                LlmPricing.microsFromPrice(2.0D),
                LlmPricing.microsFromPrice(0.04D),
                LlmPricing.microsFromPrice(8.0D)
        );
        assertEquals(52L, snapshot.totalCostMicros(pricing));
    }

    @Test
    void moduleServiceExposesTheLedgerAsAReadOnlySnapshotPricedFromConfiguration() {
        LlmUsageLedger ledger = new LlmUsageLedger();
        ledger.record("module.ax", 1_000_000L, 1_000_000L, 0L, 1_000_000L, true);
        LlmModuleService moduleService = new LlmModuleService(new TestLlmSupport.FakeConfig(tempDir), ledger);

        LlmUsageSnapshot snapshot = moduleService.usageSnapshot();

        assertEquals(1L, snapshot.module("module.ax").requests());
        assertEquals(1_000_000L, snapshot.total().inputTokens());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.byModule().clear());
        assertTrue(moduleService.usagePricing().configured(), "default prices must produce a configured pricing");
        assertEquals(8_040_000L, snapshot.totalCostMicros(moduleService.usagePricing()));
    }

    private LlmProtocolAdapter adapter(LlmUsageLedger ledger, LlmPromptCacheUsage cacheUsage) {
        LLMService service = LLMService.builder()
                .env(new FakeGameEnvironment())
                .config(new TestLlmSupport.FakeConfig(tempDir))
                .inferenceClient(new StubInferenceClient(cacheUsage))
                .embeddingRuntime(new EmbeddingRuntime())
                .cacheNamespace("test:namespace")
                .build();
        return new LlmProtocolAdapter(
                new ProtocolRuntime(Runnable::run),
                service,
                new LlmPromptFactory(() -> "deepseek-flash"),
                ledger
        );
    }

    private static TianshuEnvelope envelope(String sourceId, String requestId, boolean stream) {
        return EnvelopeBuilder.requestCapability(
                sourceId,
                LlmCapabilities.LLM_REQUEST,
                PayloadType.of("LLM_PROMPT_REQUEST"),
                new LLMPromptRequestPayload(
                        requestId,
                        List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello")),
                        stream,
                        false,
                        0,
                        LLMPromptRequestPayload.SamplingPayload.empty()
                )
        ).build();
    }

    private static boolean await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return condition.getAsBoolean();
            }
        }
        return condition.getAsBoolean();
    }

    private static final class StubInferenceClient implements LlmInferenceClient {
        private final LlmTokenUsage usage = new LlmTokenUsage(PROMPT_TOKENS, COMPLETION_TOKENS);
        private final LlmPromptCacheUsage cacheUsage;

        StubInferenceClient(LlmPromptCacheUsage cacheUsage) {
            this.cacheUsage = cacheUsage;
        }

        @Override
        public LlmGenerationResult chat(
                List<ChatMessage> messages,
                LLMPromptRequestPayload.SamplingPayload sampling,
                int maxTokens,
                boolean captureThinkingContent,
                Consumer<LlmPromptCacheUsage> cacheSink
        ) {
            if (cacheSink != null && cacheUsage.known()) {
                cacheSink.accept(cacheUsage);
            }
            return new LlmGenerationResult("done", usage);
        }

        @Override
        public CompletableFuture<LlmGenerationResult> chatStream(
                List<ChatMessage> messages,
                LLMPromptRequestPayload.SamplingPayload sampling,
                int maxTokens,
                boolean captureThinkingContent,
                Consumer<String> onToken,
                Consumer<String> onThinking,
                Consumer<LlmStreamFinish> onFinish,
                Consumer<LlmPromptCacheUsage> cacheSink
        ) {
            if (cacheSink != null && cacheUsage.known()) {
                cacheSink.accept(cacheUsage);
            }
            if (onFinish != null) {
                onFinish.accept(new LlmStreamFinish(StreamFinishType.COMPLETED, usage, null));
            }
            return CompletableFuture.completedFuture(new LlmGenerationResult("done", usage));
        }

        @Override
        public int countChatPromptTokens(List<ChatMessage> messages) {
            return PROMPT_TOKENS;
        }

        @Override
        public boolean isReady() {
            return true;
        }
    }

    private static final class RecordingContext implements ProtocolContext {
        @Override
        public void submit(TianshuEnvelope envelope) {
        }

        @Override
        public void complete(String envelopeId) {
        }

        @Override
        public void fail(String envelopeId, String reasonCode, String message, Throwable throwable) {
        }

        @Override
        public void cancel(String envelopeId, String reasonCode, String message) {
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
