package com.rheinmetal.tianshu.function.intake.repair;

import com.rheinmetal.tianshu.function.ia.protocol.IaCapabilities;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.function.intake.AsrProtocolAdapter;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.function.intake.protocol.IntakeTopics;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.function.intake.contract.AsrTextPayload;
import com.rheinmetal.tianshu.function.ia.contract.IrModuleMatch;
import com.rheinmetal.tianshu.function.ia.contract.IrResultPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistration;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistry;
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
        adapter.registerOwnedTopics(runtime);
        AsrTextRepairer repairer = new AsrTextRepairer(adapter, null);
        runtime.triggers().register(new TriggerRegistration("module.maid", List.of("酒狐"), List.of("种地")));
        repairer.initialize(voiceAccess(), DiagnosticSink.NOOP);
        RecordingHandler arbitration = new RecordingHandler();
        registerCapability(
                "test.ia",
                IaCapabilities.DIALOGUE_ARBITRATE,
                PayloadType.of("DIALOGUE_ARBITRATION_REQUEST"),
                com.rheinmetal.tianshu.function.ia.contract.DialogueArbitrationRequestPayload.class,
                EnumSet.of(PacketType.COMMAND, PacketType.REQUEST),
                arbitration
        );
        RecordingHandler irResults = new RecordingHandler();
        subscribeIrResult(irResults);

        publishAndRepair(adapter, repairer, new AsrTextPayload("九狐帮我种地", "九狐帮我种地", 42, 77L, "asr", 100L));

        IrResultPayload result = irResults.awaitPayload(IrResultPayload.class);
        assertEquals("酒狐帮我种地", result.repairedText());
        assertEquals("九狐帮我种地", result.rawText());
        assertEquals(42, result.turnId());
        assertEquals(77L, result.sessionId());
        // One entry per hit trigger word, in the order the matcher decided: wake words first, then variants.
        assertEquals(List.of(
                new IrModuleMatch("module.maid", "酒狐", "酒狐", IrModuleMatch.KIND_WAKE),
                new IrModuleMatch("module.maid", "种地", "种地", IrModuleMatch.KIND_EXTRA)
        ), result.moduleMatches(), "an auxiliary word keeps its own kind instead of passing as a wake word");
        assertTrue(result.objectMatches().isEmpty());
        assertTrue(result.timestampMillis() > 0L);
        assertTrue(arbitration.envelopes().isEmpty());
        assertTrue(runtime.deadLetters().snapshot(16).isEmpty());
        assertFalse(runtime.capabilities().capabilityIds().stream().anyMatch(id -> id.startsWith("VOICE_TRIGGER.")));
    }

    @Test
    void stoppedRepairerPublishesNothing() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        AsrProtocolAdapter adapter = new AsrProtocolAdapter(runtime);
        adapter.registerOwnedTopics(runtime);
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
        adapter.registerOwnedTopics(runtime);
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

    private TriggerRegistry voiceAccess() {
        return runtime.triggers();
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
                        IntakeTopics.IR_RESULT,
                        PayloadType.of("IR_RESULT"),
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
