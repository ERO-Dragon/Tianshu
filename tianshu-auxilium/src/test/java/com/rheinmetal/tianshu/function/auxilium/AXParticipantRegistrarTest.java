package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.bridge.IaDialogueBridge;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimRule;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueHoldTier;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(DialogueHoldTier.TEN_SECONDS, payload.descriptor().holdTier());
        assertTrue(payload.descriptor().factoryDefaultOwner(),
                "the host's own assistant is the out-of-box default owner");
        assertEquals(AXParticipantRegistrar.PARTICIPANT_ID, payload.descriptor().displayName(),
                "without a language entry the participant falls back to its id, never to an empty name");
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
        assertEquals(AXParticipantRegistrar.PARTICIPANT_ID, registerPayload.descriptor().displayName(),
                "the wake word is a wake word, not the name IA shows");
        assertEquals(AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY, registerPayload.descriptor().routeCapability());
        assertEquals(DialogueClaimProfile.defaultOwnerWithRules(
                com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimRule.any(
                        "ax.wake_word",
                        com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimCondition.wakeWord("天枢")
                )
        ), registerPayload.descriptor().claimProfile());

        registrar.unregister();

        await(() -> unregistered.get() != null);
        DialogueParticipantUnregisterPayload unregisterPayload = unregistered.get();
        assertEquals(AXModule.MODULE_ID, unregisterPayload.moduleId());
        assertEquals(AXParticipantRegistrar.PARTICIPANT_ID, unregisterPayload.participantId());
    }

    @Test
    void theDisplayNameComesFromTheLanguageTableAndNotFromTheWakeWord() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AtomicReference<DialogueParticipantRegisterPayload> registered = new AtomicReference<>();
        IaProtocolAdapter iaAdapter = new IaProtocolAdapter(runtime);
        iaAdapter.registerParticipantCapability((envelope, context) -> {
            registered.set((DialogueParticipantRegisterPayload) envelope.payload());
            context.complete(envelope.envelopeId());
        });
        AXParticipantRegistrar registrar = new AXParticipantRegistrar(
                new IaDialogueBridge(new AXProtocolAdapter(runtime)), () -> "天枢", () -> "辅星");

        registrar.register();

        await(() -> registered.get() != null);
        DialogueParticipantRegisterPayload payload = registered.get();
        assertEquals("辅星", payload.descriptor().displayName());
        assertEquals(DialogueClaimProfile.defaultOwnerWithRules(DialogueClaimRule.any(
                        "ax.wake_word",
                        DialogueClaimCondition.wakeWord("天枢"))),
                payload.descriptor().claimProfile(),
                "the wake word still drives the claim; only the shown name moved to the language table");
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
