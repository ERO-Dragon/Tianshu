package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.function.ia.payload.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueParticipantUnregisterPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AXParticipantRegistrarTest {
    @Test
    void registersAndUnregistersAxParticipantThroughProtocolCapability() {
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
        AXParticipantRegistrar registrar = new AXParticipantRegistrar(new AXProtocolAdapter(runtime), () -> "");

        registrar.register();

        await(() -> registered.get() != null);
        DialogueParticipantRegisterPayload registerPayload = registered.get();
        assertNotNull(registerPayload);
        assertEquals(AXModule.MODULE_ID, registerPayload.descriptor().moduleId());
        assertEquals(AXParticipantRegistrar.PARTICIPANT_ID, registerPayload.descriptor().participantId());
        assertEquals(AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY, registerPayload.descriptor().routeCapability());
        assertEquals(DialogueClaimProfile.DEFAULT_OWNER, registerPayload.descriptor().claimProfile());

        registrar.unregister();

        await(() -> unregistered.get() != null);
        DialogueParticipantUnregisterPayload unregisterPayload = unregistered.get();
        assertNotNull(unregisterPayload);
        assertEquals(AXModule.MODULE_ID, unregisterPayload.moduleId());
        assertEquals(AXParticipantRegistrar.PARTICIPANT_ID, unregisterPayload.participantId());
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2_000L;
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
