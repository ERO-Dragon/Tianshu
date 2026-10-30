package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.function.presence.protocol.PresenceTopics;

import com.rheinmetal.tianshu.function.presence.protocol.PresencePayloadTypes;

import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
import com.rheinmetal.tianshu.function.presence.protocol.PresenceActivityIds;
import com.rheinmetal.tianshu.function.ia.gateway.DialogueProtocolPort;
import com.rheinmetal.tianshu.function.ia.contract.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueArbitrationResultPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueParticipantUnregisterPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueSessionControlPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueSessionEventPayload;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.function.ia.contract.AsrSpeechActivityPayload;
import com.rheinmetal.tianshu.function.ia.contract.IrResultPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityType;
import com.rheinmetal.tianshu.protocol.payload.ModuleStatusPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceInteractionContextPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresencePartnerPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.EnumSet;

public final class IaProtocolAdapter extends AbstractProtocolAdapter implements DialogueProtocolPort {
    public static final String MODULE_ID = ProtocolModuleIds.IA;
    public static final String SOURCE_ID = ProtocolModuleIds.IA;

    public IaProtocolAdapter(ModuleRuntimeAccess runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard().withConcurrency(1, 64));
    }

    public void registerArbitrationCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.DIALOGUE_ARBITRATE,
                PayloadType.DIALOGUE_ARBITRATION_REQUEST,
                DialogueArbitrationRequestPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.REQUEST, PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void registerParticipantCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.DIALOGUE_PARTICIPANT_REGISTER,
                PayloadType.DIALOGUE_PARTICIPANT_REGISTER,
                DialogueParticipantRegisterPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public void registerParticipantUnregisterCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.DIALOGUE_PARTICIPANT_UNREGISTER,
                PayloadType.DIALOGUE_PARTICIPANT_UNREGISTER,
                DialogueParticipantUnregisterPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public void registerSessionControlCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.DIALOGUE_SESSION_CONTROL,
                PayloadType.DIALOGUE_SESSION_CONTROL,
                DialogueSessionControlPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public void subscribeAsrSpeechActivity(EnvelopeHandler handler) {
        subscribeTopic(
                ProtocolTopics.INPUT_ASR_SPEECH_ACTIVITY,
                PayloadType.ASR_SPEECH_ACTIVITY,
                AsrSpeechActivityPayload.class,
                BrokerType.STATELESS_FAST_PATH,
                EnumSet.of(PacketType.EVENT),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public void subscribeIrResult(EnvelopeHandler handler) {
        subscribeTopic(
                ProtocolTopics.IR_RESULT,
                PayloadType.IR_RESULT,
                IrResultPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.EVENT),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public TianshuEnvelope respondArbitrationResult(TianshuEnvelope parent, DialogueArbitrationResultPayload payload) {
        return respondTo(parent, PayloadType.DIALOGUE_ARBITRATION_RESULT, payload);
    }

    /**
     * Closes the listening state of one speech turn.
     *
     * <p>The speech module opens the mark; this module is the one that knows the turn's outcome, so it is the one
     * allowed to end it. The id comes from {@link PresenceActivityIds} so the opener and the closer cannot drift.</p>
     */
    public TianshuEnvelope publishListeningEnded(long sourceSessionId) {
        return publishTopic(
                PresenceTopics.ACTIVITY,
                PresencePayloadTypes.ACTIVITY,
                PresenceActivityPayload.ended(
                        PresenceActivityIds.listening(sourceSessionId),
                        PresenceActivityType.LISTENING
                )
        );
    }

    @Override
    public boolean hasCapabilityProvider(String capabilityId) {
        return runtime().capabilityProviderCount(capabilityId) > 0;
    }

    @Override
    public TianshuEnvelope publishSessionEvent(TianshuEnvelope parent, DialogueSessionEventPayload payload) {
        return publishTopic(parent, ProtocolTopics.DIALOGUE_SESSION_EVENTS, PayloadType.DIALOGUE_SESSION_EVENT, payload);
    }

    /**
     * Writes the partner marker state on presence's channel.
     *
     * <p>The topic and payload belong to presence; the arbiter is only the module that can compute who the
     * partner is, so it is the writer. There is no parent envelope to inherit from: this is a standalone
     * product broadcast, not a reply to anything.</p>
     */
    public TianshuEnvelope publishPartner(PresencePartnerPayload payload) {
        return publishTopic(PresenceTopics.PARTNER, PresencePayloadTypes.PARTNER, payload);
    }

    public void subscribeInteractionContext(EnvelopeHandler handler) {
        subscribeTopic(
                PresenceTopics.INTERACTION_CONTEXT,
                PresencePayloadTypes.INTERACTION_CONTEXT,
                PresenceInteractionContextPayload.class,
                BrokerType.STATELESS_FAST_PATH,
                EnumSet.of(PacketType.EVENT),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public TianshuEnvelope publishModuleStatus(ModuleStatus status) {
        return status == null ? null : publishTopic(
                ProtocolTopics.MODULE_STATUS,
                PayloadType.MODULE_STATUS,
                new ModuleStatusPayload(status)
        );
    }

    @Override
    public TianshuEnvelope deliverToCapability(TianshuEnvelope parent, String capabilityId, DialogueDeliveryPayload payload) {
        return commandCapability(parent, capabilityId, PayloadType.DIALOGUE_DELIVERY, payload);
    }
}
