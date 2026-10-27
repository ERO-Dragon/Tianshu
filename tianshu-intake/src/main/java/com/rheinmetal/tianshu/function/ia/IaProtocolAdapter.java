package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
import com.rheinmetal.tianshu.protocol.PresenceActivityIds;
import com.rheinmetal.tianshu.function.ia.gateway.DialogueProtocolPort;
import com.rheinmetal.tianshu.function.ia.contract.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueArbitrationResultPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueOwnerPreviewPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueParticipantUnregisterPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueSessionControlPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueSessionEventPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueScoreRequestPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueScoreResponsePayload;
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
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityType;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.payload.ModuleStatusPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.time.Duration;
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

    public void registerScoreCapability(String capabilityId, EnvelopeHandler handler) {
        registerCapability(
                capabilityId,
                PayloadType.DIALOGUE_SCORE_REQUEST,
                DialogueScoreRequestPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.REQUEST),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public TianshuEnvelope buildScoreRequest(TianshuEnvelope parent, String capabilityId, DialogueScoreRequestPayload payload) {
        return buildRequestCapability(parent, capabilityId, PayloadType.DIALOGUE_SCORE_REQUEST, payload);
    }

    public TianshuEnvelope submitScoreRequest(TianshuEnvelope envelope) {
        return submitPrepared(envelope);
    }

    public TianshuEnvelope respondScore(TianshuEnvelope parent, DialogueScoreResponsePayload payload) {
        return respondTo(parent, PayloadType.DIALOGUE_SCORE_RESPONSE, payload);
    }

    public ProtocolTaskHandle scheduleScoreTimeout(String requestEnvelopeId, Runnable task, Duration delay) {
        return runtime().schedule(
                ProtocolTaskSpec.builder()
                        .moduleId(MODULE_ID)
                        .lane(ExecutionLane.SCHEDULED)
                        .taskId("ia.score-timeout." + requestEnvelopeId)
                        .envelopeId(requestEnvelopeId)
                        .build(),
                task,
                delay
        );
    }

    public void registerScoreResponse(String requestEnvelopeId, EnvelopeHandler handler) {
        registerResponseHandler(
                requestEnvelopeId,
                PayloadType.DIALOGUE_SCORE_RESPONSE,
                DialogueScoreResponsePayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void unregisterScoreResponses(String requestEnvelopeId) {
        unregisterResponseHandlers(requestEnvelopeId);
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
     * Closes the listening mark of one speech turn.
     *
     * <p>The speech module opens the mark; this module is the one that knows the turn's outcome, so it is the one
     * allowed to end it. The id comes from {@link PresenceActivityIds} so the opener and the closer cannot drift.</p>
     */
    public TianshuEnvelope publishListeningEnded(long sourceSessionId) {
        return publishTopic(
                ProtocolTopics.PRESENCE_ACTIVITY,
                PayloadType.PRESENCE_ACTIVITY,
                PresenceActivityPayload.ended(
                        PresenceActivityIds.listening(sourceSessionId),
                        PresenceActivityType.LISTENING
                )
        );
    }

    public TianshuEnvelope buildPresenceContextQuery(TianshuEnvelope parent, PresenceContextQueryPayload payload) {
        return buildRequestCapability(parent, ProtocolCapabilities.PRESENCE_QUERY_CONTEXT, PayloadType.PRESENCE_CONTEXT_QUERY, payload);
    }

    public TianshuEnvelope submitPresenceContextQuery(TianshuEnvelope envelope) {
        return submitPrepared(envelope);
    }

    public int presenceContextProviderCount() {
        return runtime().capabilityProviderCount(ProtocolCapabilities.PRESENCE_QUERY_CONTEXT);
    }

    public void registerPresenceContextSnapshotResponse(String requestEnvelopeId, EnvelopeHandler handler) {
        registerResponseHandler(
                requestEnvelopeId,
                PayloadType.PRESENCE_CONTEXT_SNAPSHOT,
                PresenceContextSnapshotPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void unregisterPresenceContextResponses(String requestEnvelopeId) {
        unregisterResponseHandlers(requestEnvelopeId);
    }

    public ProtocolTaskHandle schedulePresenceTimeout(String requestEnvelopeId, Runnable task, Duration delay) {
        return runtime().schedule(
                ProtocolTaskSpec.builder()
                        .moduleId(MODULE_ID)
                        .lane(ExecutionLane.SCHEDULED)
                        .taskId("ia.presence-timeout." + requestEnvelopeId)
                        .envelopeId(requestEnvelopeId)
                        .build(),
                task,
                delay
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

    @Override
    public TianshuEnvelope publishOwnerPreview(TianshuEnvelope parent, DialogueOwnerPreviewPayload payload) {
        if (parent == null) {
            return publishTopic(ProtocolTopics.DIALOGUE_OWNER_PREVIEW, PayloadType.DIALOGUE_OWNER_PREVIEW, payload);
        }
        return publishTopic(parent, ProtocolTopics.DIALOGUE_OWNER_PREVIEW, PayloadType.DIALOGUE_OWNER_PREVIEW, payload);
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
