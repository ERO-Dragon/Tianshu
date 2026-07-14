package com.rheinmetal.tianshu.function.auxilium.core.output;

import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
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
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXOutputProcessorTest {
    @Test
    void uiAndTtsModeStreamsUiAndSpeaksCompletedSentences() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        List<String> spoken = new CopyOnWriteArrayList<>();
        registerTtsSink(runtime, spoken);
        RecordingChatSink chatSink = new RecordingChatSink();
        AXOutputProcessor processor = new AXOutputProcessor(new AXProtocolAdapter(runtime), settings(AXOutputMode.UI_AND_TTS), chatSink);

        AXOutputProcessor.AXOutputTurn turn = processor.startTurn(parentEnvelope(), context(), true);
        turn.append("This is the first sentence. ");
        turn.append("This is the second sentence");
        turn.complete("This is the first sentence. This is the second sentence");

        await(() -> spoken.size() == 2);
        assertEquals("This is the first sentence. This is the second sentence", chatSink.text.toString());
        assertEquals(List.of("This is the first sentence.", "This is the second sentence"), spoken);
        assertEquals(1, chatSink.beginCount);
        assertEquals(1, chatSink.completeCount);
    }

    @Test
    void uiOnlyModeDoesNotSendTts() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        List<String> spoken = new CopyOnWriteArrayList<>();
        registerTtsSink(runtime, spoken);
        RecordingChatSink chatSink = new RecordingChatSink();
        AXOutputProcessor processor = new AXOutputProcessor(new AXProtocolAdapter(runtime), settings(AXOutputMode.UI_ONLY), chatSink);

        AXOutputProcessor.AXOutputTurn turn = processor.startTurn(parentEnvelope(), context(), true);
        turn.complete("Only visible in UI.");

        assertEquals("Only visible in UI.", chatSink.text.toString());
        assertTrue(spoken.isEmpty());
    }

    @Test
    void ttsOnlyModeSpeaksWithoutUi() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        List<String> spoken = new CopyOnWriteArrayList<>();
        registerTtsSink(runtime, spoken);
        RecordingChatSink chatSink = new RecordingChatSink();
        AXOutputProcessor processor = new AXOutputProcessor(new AXProtocolAdapter(runtime), settings(AXOutputMode.TTS_ONLY), chatSink);

        AXOutputProcessor.AXOutputTurn turn = processor.startTurn(parentEnvelope(), context(), true);
        turn.complete("Only spoken.");

        await(() -> spoken.size() == 1);
        assertEquals("", chatSink.text.toString());
        assertEquals(0, chatSink.beginCount);
        assertEquals(List.of("Only spoken."), spoken);
    }

    @Test
    void disabledModeSuppressesUiAndTts() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        List<String> spoken = new CopyOnWriteArrayList<>();
        registerTtsSink(runtime, spoken);
        RecordingChatSink chatSink = new RecordingChatSink();
        AXOutputProcessor processor = new AXOutputProcessor(new AXProtocolAdapter(runtime), settings(AXOutputMode.DISABLED), chatSink);

        AXOutputProcessor.AXOutputTurn turn = processor.startTurn(parentEnvelope(), context(), true);
        turn.complete("No output.");

        assertEquals("", chatSink.text.toString());
        assertTrue(spoken.isEmpty());
        assertEquals(0, chatSink.beginCount);
    }

    @Test
    void taskLaneDoesNotEmitChatOrTtsOutput() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        List<String> spoken = new CopyOnWriteArrayList<>();
        registerTtsSink(runtime, spoken);
        RecordingChatSink chatSink = new RecordingChatSink();
        AXOutputProcessor processor = new AXOutputProcessor(new AXProtocolAdapter(runtime), settings(AXOutputMode.UI_AND_TTS), chatSink);

        AXOutputProcessor.AXOutputTurn turn = processor.startTurn(parentEnvelope(), context(), false);
        turn.complete("Task result.");

        assertEquals("", chatSink.text.toString());
        assertTrue(spoken.isEmpty());
    }

    private static AXOutputSettings settings(AXOutputMode mode) {
        return () -> mode;
    }

    private static AXOutputContext context() {
        return new AXOutputContext("session", "request", "turn", "player", 1L);
    }

    private static TianshuEnvelope parentEnvelope() {
        return EnvelopeBuilder.commandToCapability(
                "module.ia",
                AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY,
                PayloadType.CUSTOM,
                LLMPromptRequestPayload.EMPTY
        ).build();
    }

    private static void registerTtsSink(ProtocolRuntime runtime, List<String> spoken) {
        AdapterDefaults defaults = AdapterDefaults.standard().withConcurrency(1, 64);
        runtime.registerModule(new ModuleDescriptor(
                "module.tts.test",
                List.of(new CapabilityDescriptor(
                        ProtocolCapabilities.TTS_SPEAK,
                        PayloadType.TTS_TEXT,
                        TtsSpeakPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.COMMAND),
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
        ), (envelope, context) -> handleTts(envelope, context, spoken));
    }

    private static void handleTts(TianshuEnvelope envelope, ProtocolContext context, List<String> spoken) {
        if (envelope.payload() instanceof TtsSpeakPayload payload) {
            spoken.add(payload.text());
        }
        context.complete(envelope.envelopeId());
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static final class RecordingChatSink implements AXChatOutputSink {
        private final StringBuilder text = new StringBuilder();
        private int beginCount;
        private int completeCount;

        @Override
        public void begin(AXOutputContext context) {
            beginCount++;
        }

        @Override
        public void append(AXOutputContext context, String value) {
            text.append(value);
        }

        @Override
        public void complete(AXOutputContext context, String fullText) {
            completeCount++;
        }
    }
}
