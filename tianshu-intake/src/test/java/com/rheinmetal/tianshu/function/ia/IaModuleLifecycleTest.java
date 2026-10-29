package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTriggerGroup;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.function.ia.registry.DialogueParticipantRegistry;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.function.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.function.ia.contract.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IaModuleLifecycleTest {
    private ProtocolRuntime runtime;

    @AfterEach
    void closeRuntime() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void stopRemovesReadyCapabilityAndLifecycleCleanupIsIdempotent() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        ModuleRuntimeState runtimeState = new ModuleRuntimeState();
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        IaModule ia = registeredModule(services);
        ModuleRuntimeContext context = new ModuleRuntimeContext(runtime, services, null, runtimeState);

        ia.prepare(context);
        ia.prepare(context);
        assertTrue(runtimeState.capabilities().isReady(IaRuntimeCapabilities.ARBITRATION));

        ia.stop();
        assertFalse(runtimeState.capabilities().isReady(IaRuntimeCapabilities.ARBITRATION));
        assertDoesNotThrow(ia::stop);
        assertDoesNotThrow(ia::destroy);
        assertDoesNotThrow(ia::destroy);
    }

    @Test
    void destroyClearsParticipantStateBeforeNewWorldInstanceRegistersAgain() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        ModuleServiceRegistry firstServices = new ModuleServiceRegistry();
        IaModule first = registeredModule(firstServices);
        first.prepare(new ModuleRuntimeContext(runtime, firstServices, null, new ModuleRuntimeState()));
        firstServices.require(DialogueParticipantRegistry.class).register(participant());
        assertFalse(firstServices.require(IaModuleService.class).snapshot().participants().isEmpty());

        first.stop();
        first.destroy();
        assertTrue(firstServices.require(IaModuleService.class).snapshot().participants().isEmpty());
        assertTrue(firstServices.require(IaModuleService.class).snapshot().sessions().isEmpty());
        runtime.triggers().unregisterModule(IaProtocolAdapter.MODULE_ID);

        ModuleServiceRegistry nextServices = new ModuleServiceRegistry();
        IaModule next = registeredModule(nextServices);
        next.prepare(new ModuleRuntimeContext(runtime, nextServices, null, new ModuleRuntimeState()));
        assertTrue(nextServices.require(IaModuleService.class).snapshot().participants().isEmpty());

        nextServices.require(DialogueParticipantRegistry.class).register(participant());
        assertFalse(nextServices.require(IaModuleService.class).snapshot().participants().isEmpty());
        next.stop();
        next.destroy();
    }

    @Test
    void protocolCloseDoesNotPreventStopAndDestroy() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        IaModule ia = registeredModule(services);
        ia.prepare(new ModuleRuntimeContext(runtime, services, null, new ModuleRuntimeState()));

        runtime.close();

        assertDoesNotThrow(ia::stop);
        assertDoesNotThrow(ia::destroy);
    }

    @Test
    void aTurnThatArrivesAfterStopIsRejectedInsteadOfDelivered() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        IaModule ia = registeredModule(services);
        ia.prepare(new ModuleRuntimeContext(runtime, services, null, new ModuleRuntimeState()));
        RecordingHandler delivery = new RecordingHandler();
        runtime.registerModule(module("module.ax", new CapabilityDescriptor(
                "AX.DIALOGUE_INPUT",
                PayloadType.DIALOGUE_DELIVERY,
                DialogueDeliveryPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN
        )), delivery);
        services.require(IaModuleService.class).registerParticipant(new DialogueParticipantDescriptor(
                "ax",
                "module.ax",
                "AX",
                0,
                DialogueClaimProfile.DEFAULT_OWNER,
                com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTriggerGroup.EMPTY,
                "AX.DIALOGUE_INPUT",
                DialogueTurnProcessingPolicy.DEFAULT
        ));

        ia.stop();
        TianshuEnvelope turn = EnvelopeBuilder.commandToCapability(
                "module.ir",
                ProtocolCapabilities.DIALOGUE_ARBITRATE,
                PayloadType.DIALOGUE_ARBITRATION_REQUEST,
                new DialogueArbitrationRequestPayload(
                        "request", "module.ir", "player", "turn", 1L,
                        "hello", "hello", List.of(), List.of(),
                        System.currentTimeMillis(), System.currentTimeMillis() + 10_000L
                )
        ).build();
        runtime.submit(turn);
        sleep(200L);

        assertTrue(delivery.envelopes().isEmpty());
        assertEquals(EnvelopeStatus.COMPLETED, runtime.lifecycle().statusOf(turn.envelopeId()));
    }

    private IaModule registeredModule(ModuleServiceRegistry services) {
        IaModule ia = new IaModule(runtime);
        ia.register(new ModuleRegistrationContext(runtime, services));
        return ia;
    }

    private DialogueParticipantDescriptor participant() {
        return new DialogueParticipantDescriptor(
                "maid",
                "module.maid",
                "Maid",
                10,
                DialogueClaimProfile.DEFAULT_OWNER,
                com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTriggerGroup.EMPTY,
                "MAID.DIALOGUE_INPUT",
                DialogueTurnProcessingPolicy.DEFAULT
        );
    }

    private ModuleDescriptor module(String moduleId, CapabilityDescriptor capability) {
        return new ModuleDescriptor(
                moduleId,
                List.of(capability),
                com.rheinmetal.tianshu.protocol.ThreadPolicy.ANY,
                com.rheinmetal.tianshu.protocol.CancellationScope.SELF_ONLY,
                com.rheinmetal.tianshu.protocol.FailurePolicy.REPORT_ONLY,
                com.rheinmetal.tianshu.protocol.DeliveryPolicy.WAIT_IN_QUEUE,
                false,
                false,
                1,
                32
        );
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static final class RecordingHandler implements EnvelopeHandler {
        private final CopyOnWriteArrayList<TianshuEnvelope> envelopes = new CopyOnWriteArrayList<>();

        @Override
        public void handle(TianshuEnvelope envelope, ProtocolContext context) {
            envelopes.add(envelope);
            context.complete(envelope.envelopeId());
        }

        private List<TianshuEnvelope> envelopes() {
            return List.copyOf(envelopes);
        }
    }
}
