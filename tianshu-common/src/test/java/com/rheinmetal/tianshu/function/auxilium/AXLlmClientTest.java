package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AXLlmClientTest {
    @Test
    void asrInterruptionCancelsOnlyChatRequests() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        registerLlmSink(runtime);
        AXLlmClient client = new AXLlmClient(new AXProtocolAdapter(runtime));
        RecordingHandler chat = new RecordingHandler();
        RecordingHandler task = new RecordingHandler();
        TianshuEnvelope parent = EnvelopeBuilder.commandToCapability(
                "module.ia",
                AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY,
                PayloadType.CUSTOM,
                LLMPromptRequestPayload.EMPTY
        ).build();

        client.submit(parent, request("chat", "CHAT"), chat);
        client.submitDetached(request("task", "TASK"), task);

        client.cancelChatRequests(AXTurnCancellation.playerInterrupted("user started speaking"));

        assertEquals(1, chat.cancelled.get());
        assertEquals(0, task.cancelled.get());
    }

    @Test
    void sweepExpiredKeepsTaskRequestsOpenForSuspendedLlmTask() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        registerLlmSink(runtime);
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
                0.7f,
                false,
                false,
                lane,
                0,
                false,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))))
        );
    }

    private static void registerLlmSink(ProtocolRuntime runtime) {
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
        ), AXLlmClientTest::holdRequest);
    }

    private static void holdRequest(TianshuEnvelope envelope, ProtocolContext context) {
    }

    private static final class RecordingHandler implements AXLlmRequestHandler {
        private final AtomicInteger cancelled = new AtomicInteger();

        @Override
        public void onCancelled(AXTurnCancellation cancellation) {
            cancelled.incrementAndGet();
        }

        @Override
        public void onResult(LLMPromptResultPayload payload) {
        }
    }
}
