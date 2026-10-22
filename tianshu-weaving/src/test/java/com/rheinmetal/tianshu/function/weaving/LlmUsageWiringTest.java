package com.rheinmetal.tianshu.function.weaving;

import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmContextBudgetSnapshot;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmEngineCapabilitySnapshot;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmMtpCalibrationRequest;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmMtpCalibrationResult;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmMtpCapabilitySnapshot;
import com.rheinmetal.tianshu.function.weaving.service.LLMService;
import com.rheinmetal.tianshu.function.weaving.service.LlmInferenceClient;
import com.rheinmetal.tianshu.function.weaving.service.LlmInferenceOptions;
import com.rheinmetal.tianshu.function.weaving.usage.LlmPromptCacheUsage;
import com.rheinmetal.tianshu.function.weaving.usage.LlmPricing;
import com.rheinmetal.tianshu.function.weaving.usage.LlmUsageLedger;
import com.rheinmetal.tianshu.function.weaving.usage.LlmUsageSnapshot;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.LlmGenerationResult;
import com.rheinmetal.tianshu.libs.llm.LlmStreamFinish;
import com.rheinmetal.tianshu.libs.llm.LlmTokenUsage;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.libs.llm.StreamFinishType;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a finished request reaches the usage ledger with its module attribution and the
 * provider's cache split, and that the module service hands the result out read-only.
 */
class LlmUsageWiringTest {
    private static final int PROMPT_TOKENS = 10;
    private static final int COMPLETION_TOKENS = 4;
    private static final int CACHE_HIT_TOKENS = 7;
    private static final int CACHE_MISS_TOKENS = 3;

    @TempDir
    Path tempDir;

    @Test
    void taskRequestIsCountedOnceForTheModuleThatIssuedIt() {
        LlmUsageLedger ledger = new LlmUsageLedger();
        StubInferenceClient client = new StubInferenceClient(alwaysReportedCache());
        LlmProtocolAdapter adapter = adapter(ledger, client);

        adapter.handleLLMRequest(taskEnvelope("module.ax", "req-1"), new RecordingContext());

        assertTrue(await(() -> ledger.snapshot().total().requests() == 1L));
        LlmUsageSnapshot snapshot = ledger.snapshot();
        assertEquals(1L, snapshot.module("module.ax").requests());
        assertEquals(PROMPT_TOKENS, snapshot.module("module.ax").inputTokens());
        assertEquals(CACHE_HIT_TOKENS, snapshot.module("module.ax").cacheHitInputTokens());
        assertEquals(CACHE_MISS_TOKENS, snapshot.module("module.ax").cacheMissInputTokens());
        assertEquals(COMPLETION_TOKENS, snapshot.module("module.ax").outputTokens());
        assertEquals(1L, snapshot.module("module.ax").cacheReportedRequests());
        assertEquals(1L, snapshot.total().requests());
    }

    @Test
    void streamedTaskIsCountedOnceEvenThoughBothCallbacksShareTheRequest() {
        LlmUsageLedger ledger = new LlmUsageLedger();
        StubInferenceClient client = new StubInferenceClient(alwaysReportedCache());
        LlmProtocolAdapter adapter = adapter(ledger, client);

        adapter.handleLLMRequest(taskStreamEnvelope("module.ax", "req-stream"), new RecordingContext());

        assertTrue(await(() -> ledger.snapshot().total().requests() == 1L));
        assertEquals(1L, ledger.snapshot().total().requests());
        assertEquals(CACHE_HIT_TOKENS, ledger.snapshot().module("module.ax").cacheHitInputTokens());
    }

    @Test
    void eachRequestKeepsItsOwnModuleAttribution() {
        LlmUsageLedger ledger = new LlmUsageLedger();
        StubInferenceClient client = new StubInferenceClient(alwaysReportedCache());
        LlmProtocolAdapter adapter = adapter(ledger, client);

        adapter.handleLLMRequest(taskEnvelope("module.ax", "req-ax"), new RecordingContext());
        adapter.handleLLMRequest(taskEnvelope("module.tts", "req-tts"), new RecordingContext());

        assertTrue(await(() -> ledger.snapshot().total().requests() == 2L));
        LlmUsageSnapshot snapshot = ledger.snapshot();
        assertEquals(1L, snapshot.module("module.ax").requests());
        assertEquals(1L, snapshot.module("module.tts").requests());
        assertEquals(0L, snapshot.module("module.never-called").requests());
        assertEquals(PROMPT_TOKENS, snapshot.module("module.ax").inputTokens());
        assertEquals(PROMPT_TOKENS, snapshot.module("module.tts").inputTokens());
    }

    @Test
    void unreportedCacheSplitIsRecordedAsNoDataAndPricedAsUncached() {
        LlmUsageLedger ledger = new LlmUsageLedger();
        StubInferenceClient client = new StubInferenceClient(LlmPromptCacheUsage.unknown());
        LlmProtocolAdapter adapter = adapter(ledger, client);

        adapter.handleLLMRequest(taskEnvelope("module.ax", "req-unknown"), new RecordingContext());

        assertTrue(await(() -> ledger.snapshot().total().requests() == 1L));
        LlmUsageSnapshot snapshot = ledger.snapshot();
        assertEquals(PROMPT_TOKENS, snapshot.module("module.ax").inputTokens());
        assertEquals(0L, snapshot.module("module.ax").cacheHitInputTokens());
        assertEquals(0L, snapshot.module("module.ax").cacheMissInputTokens(),
                "an unreported split must not be recorded as a measured 0% hit rate");
        assertEquals(0L, snapshot.module("module.ax").cacheReportedRequests());
        // Nothing was measured, so the only honest reading is "no data", never 0%.
        assertEquals(-1D, snapshot.module("module.ax").hitRatio());

        // The amount is unaffected: the miss side is derived as input - hit, so a silent provider is
        // still priced as a full miss. 10 prompt tokens at 2.00 CNY/M = 2 micros per token, plus
        // 4 output tokens at 8.00 CNY/M = 8 micros per token.
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
        // One million prompt tokens served entirely from cache, plus one million generated tokens.
        ledger.record("module.ax", 1_000_000L, 1_000_000L, 0L, 1_000_000L, true);
        LlmModuleService moduleService = new LlmModuleService(new TestLlmSupport.FakeConfig(tempDir), ledger);

        LlmUsageSnapshot snapshot = moduleService.usageSnapshot();

        assertEquals(1L, snapshot.module("module.ax").requests());
        assertEquals(1_000_000L, snapshot.total().inputTokens());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.byModule().clear());
        assertTrue(moduleService.usagePricing().configured(), "default prices must produce a configured pricing");
        // One denomination for all three configured numbers (CNY per million tokens):
        // 1M cached prompt tokens x 0.04 + 1M output tokens x 8.00 = 8.04 CNY = 8_040_000 micros.
        assertEquals(8_040_000L, snapshot.totalCostMicros(moduleService.usagePricing()));
    }

    private LlmProtocolAdapter adapter(LlmUsageLedger ledger, StubInferenceClient client) {
        LLMService service = LLMService.builder()
                .env(new FakeGameEnvironment())
                .inferenceClient(client)
                .usePersistentCache(false)
                .build();
        return new LlmProtocolAdapter(
                new ProtocolRuntime(Runnable::run),
                service,
                new LlmTaskAdmissionController(0),
                ledger
        );
    }

    private static LlmPromptCacheUsage alwaysReportedCache() {
        return new LlmPromptCacheUsage(CACHE_HIT_TOKENS, CACHE_MISS_TOKENS);
    }

    private TianshuEnvelope taskEnvelope(String sourceId, String requestId) {
        return envelope(sourceId, requestId, false);
    }

    private TianshuEnvelope taskStreamEnvelope(String sourceId, String requestId) {
        return envelope(sourceId, requestId, true);
    }

    private TianshuEnvelope envelope(String sourceId, String requestId, boolean stream) {
        return EnvelopeBuilder.requestCapability(
                        sourceId,
                        ProtocolCapabilities.LLM_REQUEST,
                        PayloadType.LLM_PROMPT_REQUEST,
                        new LLMPromptRequestPayload(
                                requestId, 0, 0.7f, stream, false, false, "TASK", 0, false,
                                List.of(LLMPromptRequestPayload.ChunkPayload.message(
                                        List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))
                                ))
                        )
                )
                .build();
    }

    private static boolean await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
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

    /**
     * Minimal inference client: every lane finishes immediately with a fixed token count and the
     * cache split it was built with, so the test observes only the wiring and not the transport.
     */
    private static final class StubInferenceClient implements LlmInferenceClient {
        private final LlmTokenUsage usage = new LlmTokenUsage(PROMPT_TOKENS, COMPLETION_TOKENS);
        private final LlmPromptCacheUsage cacheUsage;

        StubInferenceClient(LlmPromptCacheUsage cacheUsage) {
            this.cacheUsage = cacheUsage;
        }

        private LlmGenerationResult result() {
            return new LlmGenerationResult("done", usage);
        }

        private void report(Consumer<LlmPromptCacheUsage> cacheSink) {
            if (cacheSink != null && cacheUsage.known()) {
                cacheSink.accept(cacheUsage);
            }
        }

        @Override public String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) { return "done"; }
        @Override public String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options) { return chat(messages, sampler, maxTokens); }
        @Override public LlmGenerationResult chatWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options) { return result(); }
        @Override public LlmGenerationResult chatWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options, Consumer<LlmPromptCacheUsage> cacheSink) { report(cacheSink); return result(); }
        @Override public void chatStream(List<ChatMessage> messages, SamplerConfig sampler, Consumer<String> onToken) { onToken.accept("done"); }
        @Override public void chatStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options, Consumer<String> onToken) { chatStream(messages, sampler, onToken); }
        @Override public CompletableFuture<String> chatStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options, Consumer<String> onToken, Consumer<LlmStreamFinish> onFinish) { return chatStreamWithUsage(messages, sampler, maxTokens, options, onToken, null, onFinish); }
        @Override public CompletableFuture<String> chatStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options, Consumer<String> onToken, Consumer<String> onThinking, Consumer<LlmStreamFinish> onFinish) {
            return chatStreamWithUsage(messages, sampler, maxTokens, options, onToken, onThinking, onFinish, null);
        }
        @Override public CompletableFuture<String> chatStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options, Consumer<String> onToken, Consumer<String> onThinking, Consumer<LlmStreamFinish> onFinish, Consumer<LlmPromptCacheUsage> cacheSink) {
            report(cacheSink);
            if (onFinish != null) {
                onFinish.accept(new LlmStreamFinish(StreamFinishType.COMPLETED, usage, null));
            }
            return CompletableFuture.completedFuture("done");
        }
        @Override public CompletableFuture<String> task(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible) { return CompletableFuture.completedFuture("done"); }
        @Override public CompletableFuture<String> task(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options) { return task(messages, sampler, maxTokens, priority, preemptible); }
        @Override public CompletableFuture<LlmGenerationResult> taskWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options) { return CompletableFuture.completedFuture(result()); }
        @Override public CompletableFuture<LlmGenerationResult> taskWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options, Consumer<LlmPromptCacheUsage> cacheSink) {
            report(cacheSink);
            return CompletableFuture.completedFuture(result());
        }
        @Override public CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, Consumer<String> onToken) { return CompletableFuture.completedFuture("done"); }
        @Override public CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options, Consumer<String> onToken) { return taskStream(messages, sampler, maxTokens, priority, preemptible, onToken); }
        @Override public CompletableFuture<LlmGenerationResult> taskStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options, Consumer<String> onToken, Consumer<LlmStreamFinish> onFinish) { return taskStreamWithUsage(messages, sampler, maxTokens, priority, preemptible, options, onToken, null, onFinish); }
        @Override public CompletableFuture<LlmGenerationResult> taskStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options, Consumer<String> onToken, Consumer<String> onThinking, Consumer<LlmStreamFinish> onFinish) {
            return taskStreamWithUsage(messages, sampler, maxTokens, priority, preemptible, options, onToken, onThinking, onFinish, null);
        }
        @Override public CompletableFuture<LlmGenerationResult> taskStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options, Consumer<String> onToken, Consumer<String> onThinking, Consumer<LlmStreamFinish> onFinish, Consumer<LlmPromptCacheUsage> cacheSink) {
            report(cacheSink);
            if (onFinish != null) {
                onFinish.accept(new LlmStreamFinish(StreamFinishType.COMPLETED, usage, null));
            }
            return CompletableFuture.completedFuture(result());
        }
        @Override public float[] embed(String text) { return new float[]{1f}; }
        @Override public float[][] embed(List<String> texts) { return texts.stream().map(ignored -> new float[]{1f}).toArray(float[][]::new); }
        @Override public List<RagSearchResult> search(String queryText, List<String> texts, int topK, float threshold) { return List.of(); }
        @Override public int countChatPromptTokens(List<ChatMessage> messages, SamplerConfig sampler) { return PROMPT_TOKENS; }
        @Override public boolean isReady() { return true; }
        @Override public boolean hasChatQueueCapacity() { return true; }
        @Override public boolean hasTaskQueueCapacity() { return true; }
        @Override public boolean hasQueueCapacity() { return true; }
        @Override public int getChatQueueSize() { return 0; }
        @Override public int getTaskQueueSize() { return 0; }
        @Override public int getQueueSize() { return 0; }
        @Override public boolean supportsThinking() { return false; }
        @Override public boolean supportsMtp() { return false; }
        @Override public LlmMtpCapabilitySnapshot getMtpCapability() { return LlmMtpCapabilitySnapshot.unsupported(); }
        @Override public LlmEngineCapabilitySnapshot getRuntimeCapabilities() { return new LlmEngineCapabilitySnapshot(true, true, false, false, false, 0); }
        @Override public LlmContextBudgetSnapshot getContextBudgetPlan() { return LlmContextBudgetSnapshot.ofConfiguredWindow(4_096); }
        @Override public LlmContextBudgetSnapshot getContextBudgetPlan(String lane) { return getContextBudgetPlan(); }
        @Override public CompletableFuture<LlmMtpCalibrationResult> calibrateMtpAsync(LlmMtpCalibrationRequest request) { return CompletableFuture.completedFuture(LlmMtpCalibrationResult.unsupported()); }
    }

    private static final class RecordingContext implements ProtocolContext {
        @Override public void submit(TianshuEnvelope envelope) {}
        @Override public void complete(String envelopeId) {}
        @Override public void fail(String envelopeId, String reasonCode, String message, Throwable throwable) {}
        @Override public void cancel(String envelopeId, String reasonCode, String message) {}
        @Override public boolean isCancelled(String envelopeId) { return false; }
        @Override public void onCancel(String envelopeId, Consumer<TianshuEnvelope> callback) {}
    }
}
