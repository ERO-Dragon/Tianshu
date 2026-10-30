package com.rheinmetal.tianshu.function.auxilium.core.llm;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class AXLlmClientTest {
    @Test
    void asrInterruptionCancelsOnlyChatRequests() throws InterruptedException {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        CountDownLatch taskStarted = new CountDownLatch(1);
        registerLlmSink(runtime, taskStarted);
        AXLlmClient client = new AXLlmClient(new AXProtocolAdapter(runtime));
        RecordingHandler chat = new RecordingHandler();
        RecordingHandler task = new RecordingHandler();
        TianshuEnvelope parent = EnvelopeBuilder.commandToCapability(
                "module.ia",
                AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY,
                PayloadType.CUSTOM,
                LLMPromptRequestPayload.EMPTY
        ).build();

        TianshuEnvelope chatEnvelope = client.submit(parent, request("chat", "SIMPLE"), chat);
        TianshuEnvelope taskEnvelope = client.submitDetached(request("task", "COMPLEX"), task);

        client.cancelChatRequests(AXTurnCancellation.playerInterrupted("user started speaking"));

        assertEquals(1, chat.cancelled.get());
        assertEquals(0, task.cancelled.get());
        assertEquals(EnvelopeStatus.CANCELLED, runtime.lifecycle().statusOf(chatEnvelope.envelopeId()));
        assertTrue(taskStarted.await(2, TimeUnit.SECONDS), "TASK request did not reach the LLM handler");
        assertEquals(EnvelopeStatus.RUNNING, runtime.lifecycle().statusOf(taskEnvelope.envelopeId()));

        assertTrue(client.handleStreamChunk(chatEnvelope.envelopeId(), LLMPromptStreamChunkPayload.chunk("chat", "ignored", 0)));
        assertEquals(0, chat.streamChunks.get());

        LLMPromptResultPayload.TokenUsagePayload usage = new LLMPromptResultPayload.TokenUsagePayload(120, 12, 8, 20, 140);
        assertTrue(client.handleResult(chatEnvelope.envelopeId(), LLMPromptResultPayload.cancelled("chat", "partial", "thinking", List.of(), usage)));
        assertEquals(0, chat.results.get());
        assertEquals(1, chat.cancellationResults.get());
        assertEquals(usage, chat.lastTerminalUsage.get());
    }

    @Test
    void targetedForegroundCancellationLeavesOtherChatRequestsAlive() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        registerLlmSink(runtime, null);
        AXLlmClient client = new AXLlmClient(new AXProtocolAdapter(runtime));
        RecordingHandler first = new RecordingHandler();
        RecordingHandler second = new RecordingHandler();

        TianshuEnvelope firstEnvelope = client.submitDetached(request("first", "SIMPLE"), first);
        TianshuEnvelope secondEnvelope = client.submitDetached(request("second", "SIMPLE"), second);

        assertTrue(client.cancelRequestAwaitTerminal(
                firstEnvelope.envelopeId(),
                AXTurnCancellation.playerInterrupted("new IA delivery")
        ));

        assertEquals(1, first.cancelled.get());
        assertEquals(0, second.cancelled.get());
        assertEquals(EnvelopeStatus.CANCELLED, runtime.lifecycle().statusOf(firstEnvelope.envelopeId()));
        assertNotEquals(EnvelopeStatus.CANCELLED, runtime.lifecycle().statusOf(secondEnvelope.envelopeId()));

        assertTrue(client.handleResult(
                firstEnvelope.envelopeId(),
                LLMPromptResultPayload.cancelled("first", "partial", "", List.of(), null)
        ));
        assertEquals(1, first.cancellationResults.get());
        assertTrue(client.handleStreamChunk(
                secondEnvelope.envelopeId(),
                LLMPromptStreamChunkPayload.chunk("second", "still active", 0)
        ));
        assertEquals(1, second.streamChunks.get());
    }

    @Test
    void sweepExpiredKeepsTaskRequestsOpenForSuspendedLlmTask() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        registerLlmSink(runtime, null);
        AtomicLong nowMillis = new AtomicLong(System.currentTimeMillis());
        AXLlmClient client = new AXLlmClient(new AXProtocolAdapter(runtime), nowMillis::get);
        RecordingHandler chat = new RecordingHandler();
        RecordingHandler task = new RecordingHandler();

        client.submitDetached(request("chat", "SIMPLE"), chat);
        client.submitDetached(request("task", "COMPLEX"), task);
        nowMillis.addAndGet(120_000L);
        client.sweepExpired();

        assertEquals(1, chat.cancelled.get());
        assertEquals(0, task.cancelled.get());
    }

    @Test
    void aTransientFailureIsResubmittedUntilTheAttemptsRunOut() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        List<String> submitted = registerRecordingLlmSink(runtime);
        List<Long> delays = new ArrayList<>();
        AXLlmClient client = immediateRetryClient(runtime, delays);
        RecordingHandler handler = new RecordingHandler();

        client.submitDetached(request("request", "SIMPLE"), handler);
        await(() -> submitted.size() == 1, "首发");

        for (int attempt = 0; attempt < 4; attempt++) {
            assertTrue(client.handleResult(submitted.get(attempt), transientFailure()));
            assertEquals(0, handler.results.get(), "重投期间不许交出终态");
            int expected = attempt + 2;
            await(() -> submitted.size() == expected, "第 " + expected + " 遍");
        }
        assertEquals(List.of(500L, 1_000L, 2_000L, 4_000L), delays);

        assertTrue(client.handleResult(submitted.get(4), transientFailure()));
        assertEquals(1, handler.results.get(), "五次失败只报一次终态");
        assertTrue(handler.lastResult.get().isFailed());
        awaitNoFurtherSubmission(submitted, 5);
    }

    @Test
    void aFinalFailureIsReportedWithoutAnotherAttempt() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        List<String> submitted = registerRecordingLlmSink(runtime);
        List<Long> delays = new ArrayList<>();
        AXLlmClient client = immediateRetryClient(runtime, delays);
        RecordingHandler handler = new RecordingHandler();

        client.submitDetached(request("request", "SIMPLE"), handler);
        await(() -> submitted.size() == 1, "首发");
        assertTrue(client.handleResult(submitted.get(0), LLMPromptResultPayload.failed(
                "request",
                "LLM_INFERENCE_FAILED",
                "REMOTE_LLM_HTTP_STATUS status=401 invalid api key"
        )));

        assertTrue(delays.isEmpty(), "参数错误不值得重投");
        assertEquals(1, handler.results.get());
        assertEquals("LLM_INFERENCE_FAILED", handler.lastResult.get().errorCode());
        awaitNoFurtherSubmission(submitted, 1);
    }

    @Test
    void aSuccessfulRetryIsReportedOnce() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        List<String> submitted = registerRecordingLlmSink(runtime);
        List<Long> delays = new ArrayList<>();
        AXLlmClient client = immediateRetryClient(runtime, delays);
        RecordingHandler handler = new RecordingHandler();

        client.submitDetached(request("request", "SIMPLE"), handler);
        await(() -> submitted.size() == 1, "首发");
        assertTrue(client.handleResult(submitted.get(0), transientFailure()));
        await(() -> submitted.size() == 2, "重投");
        assertTrue(client.handleResult(submitted.get(1), LLMPromptResultPayload.completed("request", "answer")));

        assertEquals(1, handler.results.get());
        assertTrue(handler.lastResult.get().isCompleted());
        assertEquals(0, handler.cancelled.get());
    }

    @Test
    void cancellingDuringTheBackoffGapStopsTheNextAttempt() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        List<String> submitted = registerRecordingLlmSink(runtime);
        List<Runnable> waiting = new ArrayList<>();
        AXLlmClient client = new AXLlmClient(
                new AXProtocolAdapter(runtime),
                System::currentTimeMillis,
                DiagnosticSink.NOOP,
                (delayMillis, task) -> waiting.add(task)
        );
        RecordingHandler handler = new RecordingHandler();
        TianshuEnvelope first = client.submitDetached(request("request", "SIMPLE"), handler);

        await(() -> submitted.size() == 1, "首发");
        assertTrue(client.handleResult(submitted.get(0), transientFailure()));
        assertEquals(1, waiting.size(), "退避期间既没有在途信封，也还没有下一遍");

        assertTrue(client.cancelRequest(first.envelopeId(), AXTurnCancellation.playerInterrupted("interrupted")));
        assertEquals(1, handler.cancelled.get());

        waiting.get(0).run();
        assertEquals(0, handler.results.get());
        assertEquals(1, handler.cancelled.get(), "取消只报一次");
        awaitNoFurtherSubmission(submitted, 1);
    }

    @Test
    void expiryDuringTheBackoffGapEndsTheRequestInsteadOfSendingIt() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        List<String> submitted = registerRecordingLlmSink(runtime);
        List<Runnable> waiting = new ArrayList<>();
        AtomicLong nowMillis = new AtomicLong(System.currentTimeMillis());
        AXLlmClient client = new AXLlmClient(
                new AXProtocolAdapter(runtime),
                nowMillis::get,
                DiagnosticSink.NOOP,
                (delayMillis, task) -> waiting.add(task)
        );
        RecordingHandler handler = new RecordingHandler();

        client.submitDetached(request("request", "SIMPLE"), handler);
        await(() -> submitted.size() == 1, "首发");
        assertTrue(client.handleResult(submitted.get(0), transientFailure()));
        nowMillis.addAndGet(120_000L);

        waiting.get(0).run();
        assertEquals(1, handler.cancelled.get(), "过期以取消结束，不会静默消失");
        assertEquals(0, handler.results.get());
        awaitNoFurtherSubmission(submitted, 1);
    }

    private static AXLlmClient immediateRetryClient(ProtocolRuntime runtime, List<Long> delays) {
        return new AXLlmClient(
                new AXProtocolAdapter(runtime),
                System::currentTimeMillis,
                DiagnosticSink.NOOP,
                (delayMillis, task) -> {
                    delays.add(delayMillis);
                    task.run();
                }
        );
    }

    private static LLMPromptResultPayload transientFailure() {
        return LLMPromptResultPayload.failed(
                "request",
                "LLM_INFERENCE_FAILED",
                "REMOTE_LLM_HTTP_STATUS status=503 service unavailable"
        );
    }

    /** 投递与结果都在别的线程上发生，所以等它落地，不拿"立刻"当断言。 */
    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepQuietly(5L);
        }
        fail("没有等到" + what);
    }

    /** 断言"不会再多出一遍"：投递是异步的，所以要留够时间让它有机会违背。 */
    private static void awaitNoFurtherSubmission(List<String> submitted, int expected) {
        long deadline = System.currentTimeMillis() + 250L;
        while (System.currentTimeMillis() < deadline) {
            assertEquals(expected, submitted.size(), "不该再多出一遍");
            sleepQuietly(5L);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static LLMPromptRequestPayload request(String requestId, String lane) {
        return new LLMPromptRequestPayload(
                requestId,
                0,
                null,
                false,
                false,
                lane,
                0,
                false,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))))
        );
    }

    private static void registerLlmSink(ProtocolRuntime runtime, CountDownLatch taskStarted) {
        runtime.registerModule(llmModule(), (envelope, context) -> holdRequest(envelope, taskStarted));
    }

    /** 同一个 LLM 能力，但记下每一次真正投出去的信封，重投用这些 id 喂结果。 */
    private static List<String> registerRecordingLlmSink(ProtocolRuntime runtime) {
        List<String> submitted = new CopyOnWriteArrayList<>();
        runtime.registerModule(llmModule(), (envelope, context) -> submitted.add(envelope.envelopeId()));
        return submitted;
    }

    private static ModuleDescriptor llmModule() {
        AdapterDefaults defaults = AdapterDefaults.standard();
        return new ModuleDescriptor(
                "module.llm.test",
                List.of(new CapabilityDescriptor(
                        "LLM.REQUEST",
                        PayloadType.of("LLM_PROMPT_REQUEST"),
                        LLMPromptRequestPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.REQUEST),
                        Priority.LOW,
                        CompletionPolicy.MANUAL_COMPLETE
                )),
                defaults.threadPolicy(),
                defaults.cancellationScope(),
                defaults.failurePolicy(),
                defaults.deliveryPolicy(),
                defaults.cancellable(),
                defaults.supportsStreaming(),
                defaults.maxConcurrency(),
                defaults.queueCapacity()
        );
    }

    private static void holdRequest(TianshuEnvelope envelope, CountDownLatch taskStarted) {
        if (taskStarted != null
                && envelope.payload() instanceof LLMPromptRequestPayload payload
                && "task".equals(payload.requestId())) {
            taskStarted.countDown();
        }
    }

    private static final class RecordingHandler implements AXLlmRequestHandler {
        private final AtomicInteger cancelled = new AtomicInteger();
        private final AtomicInteger results = new AtomicInteger();
        private final AtomicInteger streamChunks = new AtomicInteger();
        private final AtomicInteger cancellationResults = new AtomicInteger();
        private final AtomicReference<LLMPromptResultPayload> lastResult = new AtomicReference<>();
        private final AtomicReference<LLMPromptResultPayload.TokenUsagePayload> lastTerminalUsage = new AtomicReference<>();

        @Override
        public void onStreamChunk(LLMPromptStreamChunkPayload payload) {
            streamChunks.incrementAndGet();
        }

        @Override
        public void onCancelled(AXTurnCancellation cancellation) {
            cancelled.incrementAndGet();
        }

        @Override
        public void onCancellationResult(LLMPromptResultPayload payload) {
            cancellationResults.incrementAndGet();
            lastTerminalUsage.set(payload == null ? null : payload.usage());
        }

        @Override
        public void onResult(LLMPromptResultPayload payload) {
            results.incrementAndGet();
            lastResult.set(payload);
        }
    }
}
