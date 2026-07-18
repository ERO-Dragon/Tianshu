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
import com.rheinmetal.tianshu.protocol.payload.TtsVoiceOptions;
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
        List<PacketType> packets = new CopyOnWriteArrayList<>();
        registerTtsSink(runtime, spoken, packets);
        RecordingChatSink chatSink = new RecordingChatSink();
        AXOutputProcessor processor = new AXOutputProcessor(new AXProtocolAdapter(runtime), settings(AXOutputMode.UI_AND_TTS), chatSink);

        AXOutputProcessor.AXOutputTurn turn = processor.startTurn(parentEnvelope(), context(), true);
        turn.append("This is the first sentence. ");
        turn.append("This is the second sentence");
        turn.complete("This is the first sentence. This is the second sentence");

        await(() -> spoken.size() == 2 && packets.size() == 3);
        assertEquals("This is the first sentence. This is the second sentence", chatSink.text.toString());
        assertEquals(List.of("This is the first sentence.", "This is the second sentence"), spoken);
        assertEquals(List.of(PacketType.STREAM_CHUNK, PacketType.STREAM_CHUNK, PacketType.STREAM_END), List.copyOf(packets));
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

    @Test
    void axVoiceDefaultsAreForwardedOnEverySentenceOfTheSession() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        List<TtsVoiceOptions> voices = new CopyOnWriteArrayList<>();
        registerTtsSink(runtime, new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(), voices);
        AXOutputSettings settings = new AXOutputSettings() {
            @Override public AXOutputMode outputMode() { return AXOutputMode.TTS_ONLY; }
            @Override public TtsVoiceOptions ttsVoiceOptions() { return new TtsVoiceOptions("ax:voice", 1.2F, 3); }
        };
        AXOutputProcessor processor = new AXOutputProcessor(new AXProtocolAdapter(runtime), settings, AXChatOutputSink.NOOP);

        processor.startTurn(parentEnvelope(), context(), true).complete("First. Second.");

        await(() -> voices.size() == 3);
        assertTrue(voices.stream().allMatch(voice -> voice.equals(new TtsVoiceOptions("ax:voice", 1.2F, 3))));
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
        registerTtsSink(runtime, spoken, new CopyOnWriteArrayList<>());
    }

    private static void registerTtsSink(ProtocolRuntime runtime, List<String> spoken, List<PacketType> packets) {
        registerTtsSink(runtime, spoken, packets, new CopyOnWriteArrayList<>());
    }

    private static void registerTtsSink(
            ProtocolRuntime runtime,
            List<String> spoken,
            List<PacketType> packets,
            List<TtsVoiceOptions> voices
    ) {
        AdapterDefaults defaults = AdapterDefaults.standard().withConcurrency(1, 64);
        runtime.registerModule(new ModuleDescriptor(
                "module.tts.test",
                List.of(new CapabilityDescriptor(
                        ProtocolCapabilities.TTS_SPEAK,
                        PayloadType.TTS_TEXT,
                        TtsSpeakPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.COMMAND, PacketType.STREAM_CHUNK, PacketType.STREAM_END),
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
        ), (envelope, context) -> {
            if (envelope.payload() instanceof TtsSpeakPayload payload) {
                voices.add(payload.voice());
            }
            handleTts(envelope, context, spoken, packets);
        });
    }

    private static void handleTts(TianshuEnvelope envelope, ProtocolContext context, List<String> spoken, List<PacketType> packets) {
        packets.add(envelope.header().packetType());
        if (envelope.payload() instanceof TtsSpeakPayload payload) {
            if (!payload.text().isBlank()) {
                spoken.add(payload.text());
            }
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
