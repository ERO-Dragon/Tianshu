package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.function.ia.payload.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueParticipantUnregisterPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AXParticipantRegistrarTest {
    @Test
    void emptyWakeWordDoesNotRegisterDialogueParticipant() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AtomicReference<DialogueParticipantRegisterPayload> registered = new AtomicReference<>();
        IaProtocolAdapter iaAdapter = new IaProtocolAdapter(runtime);
        iaAdapter.registerParticipantCapability((envelope, context) -> {
            registered.set((DialogueParticipantRegisterPayload) envelope.payload());
            context.complete(envelope.envelopeId());
        });
        AXParticipantRegistrar registrar = new AXParticipantRegistrar(new AXProtocolAdapter(runtime), () -> "");

        registrar.register();

        await(() -> registered.get() != null, 150L);
        assertNull(registered.get());
    }

    @Test
    void nonBlankWakeWordRegistersAndUnregistersAxParticipantThroughProtocolCapability() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AtomicReference<DialogueParticipantRegisterPayload> registered = new AtomicReference<>();
        AtomicReference<DialogueParticipantUnregisterPayload> unregistered = new AtomicReference<>();
        IaProtocolAdapter iaAdapter = new IaProtocolAdapter(runtime);
        iaAdapter.registerParticipantCapability((envelope, context) -> {
            registered.set((DialogueParticipantRegisterPayload) envelope.payload());
            context.complete(envelope.envelopeId());
        });
        iaAdapter.registerParticipantUnregisterCapability((envelope, context) -> {
            unregistered.set((DialogueParticipantUnregisterPayload) envelope.payload());
            context.complete(envelope.envelopeId());
        });
        AXParticipantRegistrar registrar = new AXParticipantRegistrar(new AXProtocolAdapter(runtime), () -> "天枢");

        registrar.register();

        await(() -> registered.get() != null);
        DialogueParticipantRegisterPayload registerPayload = registered.get();
        assertEquals(AXModule.MODULE_ID, registerPayload.descriptor().moduleId());
        assertEquals(AXParticipantRegistrar.PARTICIPANT_ID, registerPayload.descriptor().participantId());
        assertEquals("天枢", registerPayload.descriptor().displayName());
        assertEquals(AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY, registerPayload.descriptor().routeCapability());
        assertEquals(DialogueClaimProfile.defaultOwnerWithRules(
                com.rheinmetal.tianshu.function.ia.model.DialogueClaimRule.anyStrong(
                        "ax.wake_word",
                        com.rheinmetal.tianshu.function.ia.model.DialogueAttentionDecay.SLOW,
                        com.rheinmetal.tianshu.function.ia.model.DialogueClaimCondition.wakeWord("天枢")
                )
        ), registerPayload.descriptor().claimProfile());

        registrar.unregister();

        await(() -> unregistered.get() != null);
        DialogueParticipantUnregisterPayload unregisterPayload = unregistered.get();
        assertEquals(AXModule.MODULE_ID, unregisterPayload.moduleId());
        assertEquals(AXParticipantRegistrar.PARTICIPANT_ID, unregisterPayload.participantId());
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        await(condition, 2_000L);
    }

    private static void await(java.util.function.BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
