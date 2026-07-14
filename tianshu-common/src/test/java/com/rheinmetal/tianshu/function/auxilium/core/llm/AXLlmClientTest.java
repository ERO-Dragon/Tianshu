package com.rheinmetal.tianshu.function.auxilium.core.llm;

import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        TianshuEnvelope chatEnvelope = client.submit(parent, request("chat", "CHAT"), chat);
        TianshuEnvelope taskEnvelope = client.submitDetached(request("task", "TASK"), task);

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
    void sweepExpiredKeepsTaskRequestsOpenForSuspendedLlmTask() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        registerLlmSink(runtime, null);
        AtomicLong nowMillis = new AtomicLong(System.currentTimeMillis());
        AXLlmClient client = new AXLlmClient(new AXProtocolAdapter(runtime), nowMillis::get);
        RecordingHandler chat = new RecordingHandler();
        RecordingHandler task = new RecordingHandler();

        client.submitDetached(request("chat", "CHAT"), chat);
        client.submitDetached(request("task", "TASK"), task);
        nowMillis.addAndGet(120_000L);
        client.sweepExpired();

        assertEquals(1, chat.cancelled.get());
        assertEquals(0, task.cancelled.get());
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
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.llm.test",
                List.of(new CapabilityDescriptor(
                        ProtocolCapabilities.LLM_REQUEST,
                        PayloadType.LLM_PROMPT_REQUEST,
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
        ), (envelope, context) -> holdRequest(envelope, taskStarted));
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
        }
    }
}
