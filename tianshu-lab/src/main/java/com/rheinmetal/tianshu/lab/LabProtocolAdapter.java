package com.rheinmetal.tianshu.lab;

import com.rheinmetal.tianshu.ia.protocol.IaCapabilities;
import com.rheinmetal.tianshu.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.ia.contract.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.ia.contract.DialogueParticipantUnregisterPayload;
import com.rheinmetal.tianshu.ia.contract.DialogueSessionControlPayload;
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
import com.rheinmetal.tianshu.protocol.payload.TextPayload;
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
    public static final String GAMMA_CAPABILITY = "LAB.GAMMA.DIALOGUE_INPUT";
    /**
     * A capability the bench owns only to watch the centre route: the provider and the caller are the same module,
     * so nothing outside the bench has to be installed for the check to mean something.
     */
    public static final String ECHO_CAPABILITY = "LAB.ACCEPTANCE.ECHO";
    /**
     * One capability two modules declare at once.
     *
     * <p>Two providers on one capability is the case the centre has to get right: every holder of a declaration is
     * supposed to see the submission, and a second declaration must not silently replace the first. The bench registers
     * this one under its own id and under a peer id to have both without another mod installed.</p>
     */
    public static final String SHARED_CAPABILITY = "LAB.ACCEPTANCE.SHARED";

    public LabProtocolAdapter(ModuleRuntimeAccess runtime) {
        this(MODULE_ID, runtime);
    }

    /** The same adapter under another module id: a second module declaring capabilities, without a second jar. */
    public LabProtocolAdapter(String moduleId, ModuleRuntimeAccess runtime) {
        super(moduleId, moduleId, runtime, AdapterDefaults.standard());
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

    /** Declares the echo capability the acceptance run uses to see the centre deliver to its own module. */
    public void registerEcho(EnvelopeHandler handler) {
        registerCapability(
                ECHO_CAPABILITY,
                PayloadType.TEXT,
                TextPayload.class,
                BrokerType.STATELESS_FAST_PATH,
                EnumSet.of(PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    /** Declares the capability this bench and its peer both claim. */
    public void registerShared(EnvelopeHandler handler) {
        registerCapability(
                SHARED_CAPABILITY,
                PayloadType.TEXT,
                TextPayload.class,
                BrokerType.STATELESS_FAST_PATH,
                EnumSet.of(PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    /** Sends one command to the shared capability: every module that declared it is supposed to see it. */
    public void submitShared(String text) {
        commandCapability(SHARED_CAPABILITY, PayloadType.TEXT, new TextPayload(text));
    }

    /** Sends one command to the echo capability; like any module call, the centre decides where it lands. */
    public void echo(String text) {
        commandCapability(ECHO_CAPABILITY, PayloadType.TEXT, new TextPayload(text));
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

    /** Removes one participant; used when a page field makes that participant's condition invalid. */
    public void unregisterParticipant(String participantId) {
        commandCapability(
                IaCapabilities.DIALOGUE_PARTICIPANT_UNREGISTER,
                PayloadType.of("DIALOGUE_PARTICIPANT_UNREGISTER"),
                new DialogueParticipantUnregisterPayload(
                        MODULE_ID, participantId, false, System.currentTimeMillis()));
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