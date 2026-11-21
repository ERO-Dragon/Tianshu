package com.rheinmetal.tianshu.dubhe.dialogue.bridge;

import com.rheinmetal.tianshu.dubhe.DubheModule;
import com.rheinmetal.tianshu.dubhe.DubheParticipantRegistrar;
import com.rheinmetal.tianshu.dubhe.DubheProtocolAdapter;
import com.rheinmetal.tianshu.dubhe.dialogue.input.DubheDialogueInput;
import com.rheinmetal.tianshu.dubhe.dialogue.input.DubheMatchedObject;
import com.rheinmetal.tianshu.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.ia.contract.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.ia.contract.DialogueParticipantUnregisterPayload;
import com.rheinmetal.tianshu.ia.contract.DialogueSessionControlPayload;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSessionControlAction;

import java.util.EnumSet;
import java.util.Objects;

/**
 * The only place this module touches the intake module's vocabulary.
 *
 * <p>The dialogue domain is an optional integration here: a spoken turn, a participant registration and a
 * session command all belong to it, so every one of them is expressed through {@link DubheDialogueChannel} and
 * translated here. This class is therefore instantiated only when that module is installed — the class
 * reference itself is what would fail to load otherwise.</p>
 */
public final class IaDialogueBridge implements DubheDialogueChannel {

    private final DubheProtocolAdapter adapter;

    public IaDialogueBridge(DubheProtocolAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    @Override
    public void openInbound(DubheDialogueInputHandler handler) {
        Objects.requireNonNull(handler, "handler");
        adapter.registerCapabilityWith(
                DubheProtocolAdapter.DIALOGUE_INPUT_CAPABILITY,
                PayloadType.of("DIALOGUE_DELIVERY"),
                DialogueDeliveryPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                (envelope, context) -> {
                    if (!(envelope.payload() instanceof DialogueDeliveryPayload payload)) {
                        context.fail(envelope.envelopeId(), "INVALID_PAYLOAD",
                                "AX dialogue delivery payload is invalid", null);
                        return;
                    }
                    handler.handle(envelope, toInput(payload), context);
                });
    }

    @Override
    public boolean participantRegistrationAvailable() {
        return adapter.dialogueParticipantRegistrationProviderCount() > 0;
    }

    @Override
    public void registerParticipant(DialogueParticipantDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        adapter.commandCapabilityWith(
                "DIALOGUE.PARTICIPANT_REGISTER",
                PayloadType.of("DIALOGUE_PARTICIPANT_REGISTER"),
                new DialogueParticipantRegisterPayload(descriptor, System.currentTimeMillis()));
    }

    @Override
    public void unregisterParticipant() {
        adapter.commandCapabilityWith(
                "DIALOGUE.PARTICIPANT_UNREGISTER",
                PayloadType.of("DIALOGUE_PARTICIPANT_UNREGISTER"),
                new DialogueParticipantUnregisterPayload(
                        DubheModule.MODULE_ID, DubheParticipantRegistrar.PARTICIPANT_ID, false,
                        System.currentTimeMillis()));
    }

    @Override
    public void releaseSession(TianshuEnvelope parent, DubheDialogueInput input, DialogueReleaseReason reason) {
        sessionControl(parent, input, DialogueSessionControlAction.RELEASE,
                reason == null ? DialogueReleaseReason.OWNER_COMPLETED : reason, 0L);
    }

    @Override
    public void extendSession(TianshuEnvelope parent, DubheDialogueInput input, long requestedProcessingMillis) {
        if (requestedProcessingMillis <= 0L) {
            return;
        }
        sessionControl(parent, input, DialogueSessionControlAction.EXTEND_PROCESSING, null,
                requestedProcessingMillis);
    }

    private void sessionControl(TianshuEnvelope parent, DubheDialogueInput input,
                                DialogueSessionControlAction action, DialogueReleaseReason reason,
                                long requestedProcessingMillis) {
        if (parent == null || input == null || input.sessionId().isBlank()) {
            return;
        }
        adapter.commandCapabilityWith(
                parent,
                "DIALOGUE.SESSION_CONTROL",
                PayloadType.of("DIALOGUE_SESSION_CONTROL"),
                new DialogueSessionControlPayload(
                        input.sessionId(),
                        DubheModule.MODULE_ID,
                        DubheParticipantRegistrar.PARTICIPANT_ID,
                        action,
                        reason,
                        requestedProcessingMillis,
                        System.currentTimeMillis()));
    }

    /** The one translation from the partner's payload into this module's input type. */
    private static DubheDialogueInput toInput(DialogueDeliveryPayload payload) {
        return new DubheDialogueInput(
                payload.playerId(),
                payload.sessionId(),
                payload.turnId().isBlank() ? payload.requestId() : payload.turnId(),
                payload.repairedText(),
                payload.contextSnapshot(),
                payload.interactionHints(),
                payload.matchedEntityRefs(),
                payload.displayNames(),
                payload.objectMatches().stream()
                        .map(match -> new DubheMatchedObject(
                                match.spokenWord(), match.registryId(), match.registryKind(), match.languageKey()))
                        .filter(DubheMatchedObject::isMeaningful)
                        .toList(),
                payload.expireAtMillis());
    }
}
