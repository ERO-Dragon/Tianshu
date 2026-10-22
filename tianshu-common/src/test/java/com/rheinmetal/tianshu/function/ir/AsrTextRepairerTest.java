package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.function.asr.AsrProtocolAdapter;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.AsrTextPayload;
import com.rheinmetal.tianshu.protocol.payload.IrResultPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceAccess;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceSnapshot;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistration;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repair lane used to be a module that subscribed to the raw speech topic; it is now called
 * directly by the speech module. These tests exercise it the way the speech module does — publish the
 * raw text, hand the same payload to the repairer — and assert the public result topic is unchanged.
 */
class AsrTextRepairerTest {
    private ProtocolRuntime runtime;

    @AfterEach
    void closeRuntime() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void repairsMisheardWordsAndPublishesCompleteAnalysisWithoutCallingIaCapability() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        AsrProtocolAdapter adapter = new AsrProtocolAdapter(runtime);
        AsrTextRepairer repairer = new AsrTextRepairer(adapter, null);
        runtime.voiceTriggers().register(new VoiceTriggerRegistration("module.maid", List.of("酒狐"), List.of("种地")));
        repairer.initialize(voiceAccess(), DiagnosticSink.NOOP);
        RecordingHandler arbitration = new RecordingHandler();
        registerCapability(
                "test.ia",
                ProtocolCapabilities.DIALOGUE_ARBITRATE,
                PayloadType.DIALOGUE_ARBITRATION_REQUEST,
                com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueArbitrationRequestPayload.class,
                EnumSet.of(PacketType.COMMAND, PacketType.REQUEST),
                arbitration
        );
        RecordingHandler irResults = new RecordingHandler();
        subscribeIrResult(irResults);

        publishAndRepair(adapter, repairer, new AsrTextPayload("九狐帮我种地", "九狐帮我种地", 42, 77L, "asr", 100L));

        IrResultPayload result = irResults.awaitPayload(IrResultPayload.class);
        assertEquals("酒狐帮我种地", result.repairedText());
        assertEquals(42, result.turnId());
        assertEquals(77L, result.sessionId());
        assertEquals(1, result.voiceMatches().size());
        assertEquals("module.maid", result.voiceMatches().get(0).moduleId());
        assertEquals(List.of("酒狐"), result.voiceMatches().get(0).matchedWakeWords());
        assertEquals(List.of("种地"), result.voiceMatches().get(0).matchedExtraWords());
        assertTrue(result.matchedItemIds().isEmpty());
        assertTrue(result.matchedEntityTypeIds().isEmpty());
        assertTrue(result.timestampMillis() > 0L);
        assertTrue(arbitration.envelopes().isEmpty());
        assertTrue(runtime.deadLetters().snapshot(16).isEmpty());
        assertFalse(runtime.capabilities().capabilityIds().stream().anyMatch(id -> id.startsWith("VOICE_TRIGGER.")));
    }

    @Test
    void stoppedRepairerPublishesNothing() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        AsrProtocolAdapter adapter = new AsrProtocolAdapter(runtime);
        AsrTextRepairer repairer = new AsrTextRepairer(adapter, null);
        repairer.initialize(voiceAccess(), DiagnosticSink.NOOP);
        RecordingHandler irResults = new RecordingHandler();
        subscribeIrResult(irResults);

        repairer.stop();
        publishAndRepair(adapter, repairer, new AsrTextPayload("退出世界前的输入", "退出世界前的输入", 7, 88L, "continuous", 100L));

        assertTrue(irResults.envelopes().isEmpty());
    }

    @Test
    void initializeAfterStopAcceptsNewWorldInput() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        AsrProtocolAdapter adapter = new AsrProtocolAdapter(runtime);
        AsrTextRepairer repairer = new AsrTextRepairer(adapter, null);
        RecordingHandler irResults = new RecordingHandler();
        subscribeIrResult(irResults);

        repairer.stop();
        repairer.initialize(voiceAccess(), DiagnosticSink.NOOP);
        publishAndRepair(adapter, repairer, new AsrTextPayload("重新进入世界", "重新进入世界", 8, 89L, "continuous", 200L));

        IrResultPayload result = irResults.awaitPayload(IrResultPayload.class);
        assertEquals("重新进入世界", result.repairedText());
        assertEquals(89L, result.sessionId());
    }

    private void publishAndRepair(AsrProtocolAdapter adapter, AsrTextRepairer repairer, AsrTextPayload payload) {
        TianshuEnvelope published = adapter.publishFinalText(payload);
        repairer.process(payload, published);
    }

    private VoiceResourceAccess voiceAccess() {
        return new VoiceResourceAccess() {
            @Override
            public VoiceTriggerRegistry voiceTriggers() {
                return runtime.voiceTriggers();
            }

            @Override
            public VoiceResourceSnapshot snapshot() {
                return null;
            }

            @Override
            public Path resolveHotwordsFile(String language) {
                return null;
            }

            @Override
            public void addChangeListener(Consumer<VoiceResourceSnapshot> listener) {
            }

            @Override
            public void removeChangeListener(Consumer<VoiceResourceSnapshot> listener) {
            }
        };
    }

    private void registerCapability(String moduleId, String capabilityId, PayloadType payloadType, Class<?> payloadClass, EnumSet<PacketType> packets, EnvelopeHandler handler) {
        runtime.registerModule(
                descriptor(moduleId, new CapabilityDescriptor(
                        capabilityId,
                        payloadType,
                        payloadClass.asSubclass(com.rheinmetal.tianshu.protocol.ITianshuPayload.class),
                        BrokerType.BOUNDED_QUEUE,
                        packets,
                        Priority.LOW,
                        CompletionPolicy.AUTO_COMPLETE_ON_RETURN
                )),
                handler
        );
    }

    private void subscribeIrResult(EnvelopeHandler handler) {
        runtime.subscribeTopic(
                descriptor("test.ir_result", List.of()),
                new TopicSubscriptionDescriptor(
                        ProtocolTopics.IR_RESULT,
                        PayloadType.IR_RESULT,
                        IrResultPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.EVENT),
                        Priority.LOW,
                        CompletionPolicy.AUTO_COMPLETE_ON_RETURN
                ),
                handler
        );
    }

    private ModuleDescriptor descriptor(String moduleId, CapabilityDescriptor capability) {
        return descriptor(moduleId, List.of(capability));
    }

    private ModuleDescriptor descriptor(String moduleId, List<CapabilityDescriptor> capabilities) {
        return new ModuleDescriptor(
                moduleId,
                capabilities,
                com.rheinmetal.tianshu.protocol.ThreadPolicy.ANY,
                com.rheinmetal.tianshu.protocol.CancellationScope.SELF_ONLY,
                com.rheinmetal.tianshu.protocol.FailurePolicy.REPORT_ONLY,
                com.rheinmetal.tianshu.protocol.DeliveryPolicy.WAIT_IN_QUEUE,
                false,
                false,
                1,
                16
        );
    }

    private static final class RecordingHandler implements EnvelopeHandler {
        private final List<TianshuEnvelope> envelopes = new CopyOnWriteArrayList<>();

        @Override
        public void handle(TianshuEnvelope envelope, ProtocolContext context) {
            envelopes.add(envelope);
            context.complete(envelope.envelopeId());
        }

        private List<TianshuEnvelope> envelopes() {
            return new ArrayList<>(envelopes);
        }

        private <T> T awaitPayload(Class<T> type) {
            long deadline = System.currentTimeMillis() + 3_000L;
            while (System.currentTimeMillis() < deadline) {
                for (TianshuEnvelope envelope : envelopes) {
                    if (type.isInstance(envelope.payload())) {
                        return type.cast(envelope.payload());
                    }
                }
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            throw new AssertionError("Timed out waiting for payload " + type.getSimpleName() + ", received " + envelopes.size() + " envelope(s)");
        }
    }
}
