package com.rheinmetal.tianshu.function.lab;

import com.rheinmetal.tianshu.function.ia.protocol.IaCapabilities;
import com.rheinmetal.tianshu.function.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueParticipantUnregisterPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueSessionControlPayload;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSessionControlAction;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

import java.util.EnumSet;

/**
 * The bench's only contact with the IA vocabulary.
 *
 * <p>Everything here uses the public contract an external module uses: register a participant, receive
 * {@code DIALOGUE_DELIVERY} on a capability, control the session it owns. Nothing reaches into IA's
 * registry or its decision policy, which is what makes the bench a real integration test rather than a
 * second implementation.</p>
 */
public final class LabProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "module.lab";
    public static final String ALPHA_CAPABILITY = "LAB.ALPHA.DIALOGUE_INPUT";
    public static final String BETA_CAPABILITY = "LAB.BETA.DIALOGUE_INPUT";

    public LabProtocolAdapter(ModuleRuntimeAccess runtime) {
        super(MODULE_ID, MODULE_ID, runtime, AdapterDefaults.standard());
    }

    /** One delivery capability per participant, so the bench can name the owner of a session it holds. */
    public void registerDelivery(String capabilityId, EnvelopeHandler handler) {
        registerCapability(
                capabilityId,
                PayloadType.of("DIALOGUE_DELIVERY"),
                DialogueDeliveryPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void registerParticipant(DialogueParticipantDescriptor descriptor) {
        commandCapability(
                IaCapabilities.DIALOGUE_PARTICIPANT_REGISTER,
                PayloadType.of("DIALOGUE_PARTICIPANT_REGISTER"),
                new DialogueParticipantRegisterPayload(descriptor, System.currentTimeMillis()));
    }

    public void unregisterParticipants() {
        commandCapability(
                IaCapabilities.DIALOGUE_PARTICIPANT_UNREGISTER,
                PayloadType.of("DIALOGUE_PARTICIPANT_UNREGISTER"),
                new DialogueParticipantUnregisterPayload(MODULE_ID, "", true, System.currentTimeMillis()));
    }

    public void releaseSession(String sessionId, String participantId) {
        sessionControl(sessionId, participantId, DialogueSessionControlAction.RELEASE,
                DialogueReleaseReason.OWNER_COMPLETED, 0L);
    }

    public void extendSession(String sessionId, String participantId, long requestedMillis) {
        sessionControl(sessionId, participantId, DialogueSessionControlAction.EXTEND_PROCESSING,
                null, requestedMillis);
    }

    private void sessionControl(String sessionId, String participantId,
                                DialogueSessionControlAction action, DialogueReleaseReason reason,
                                long requestedMillis) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        commandCapability(
                IaCapabilities.DIALOGUE_SESSION_CONTROL,
                PayloadType.of("DIALOGUE_SESSION_CONTROL"),
                new DialogueSessionControlPayload(
                        sessionId,
                        MODULE_ID,
                        participantId,
                        action,
                        reason,
                        requestedMillis,
                        System.currentTimeMillis()));
    }
}