package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.bridge.IaDialogueBridge;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueAttentionDecay;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimRule;
import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimMode;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTriggerGroup;
import com.rheinmetal.tianshu.function.ia.contract.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueParticipantUnregisterPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AXParticipantRegistrarTest {
    @Test
    void emptyWakeWordRegistersAxAsDefaultParticipantWithoutTrigger() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AtomicReference<DialogueParticipantRegisterPayload> registered = new AtomicReference<>();
        IaProtocolAdapter iaAdapter = new IaProtocolAdapter(runtime);
        iaAdapter.registerParticipantCapability((envelope, context) -> {
            registered.set((DialogueParticipantRegisterPayload) envelope.payload());
            context.complete(envelope.envelopeId());
        });
        AXParticipantRegistrar registrar = new AXParticipantRegistrar(new IaDialogueBridge(new AXProtocolAdapter(runtime)), () -> "");

        registrar.register();

        await(() -> registered.get() != null);
        DialogueParticipantRegisterPayload payload = registered.get();
        assertNotNull(payload);
        assertEquals(DialogueClaimMode.DEFAULT_OWNER, payload.descriptor().claimProfile().mode());
        assertEquals(DialogueTriggerGroup.EMPTY, payload.descriptor().triggerGroup());
        assertEquals(0.4D, payload.descriptor().fallbackScore());
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
        AXParticipantRegistrar registrar = new AXParticipantRegistrar(new IaDialogueBridge(new AXProtocolAdapter(runtime)), () -> "天枢");

        registrar.register();

        await(() -> registered.get() != null);
        DialogueParticipantRegisterPayload registerPayload = registered.get();
        assertEquals(AXModule.MODULE_ID, registerPayload.descriptor().moduleId());
        assertEquals(AXParticipantRegistrar.PARTICIPANT_ID, registerPayload.descriptor().participantId());
        assertEquals("天枢", registerPayload.descriptor().displayName());
        assertEquals(AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY, registerPayload.descriptor().routeCapability());
        assertEquals(DialogueClaimProfile.defaultOwnerWithRules(
                com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimRule.anyStrong(
                        "ax.wake_word",
                        com.rheinmetal.tianshu.protocol.dialogue.model.DialogueAttentionDecay.SLOW,
                        com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimCondition.wakeWord("天枢")
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
