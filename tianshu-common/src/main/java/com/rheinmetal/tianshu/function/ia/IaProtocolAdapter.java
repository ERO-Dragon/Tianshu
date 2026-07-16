package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.function.ia.gateway.DialogueProtocolPort;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueArbitrationResultPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueLlmUsageAuthorizationRequestPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueLlmUsageAuthorizationResultPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueOwnerPreviewPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueParticipantUnregisterPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueSessionControlPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueSessionEventPayload;
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
import com.rheinmetal.tianshu.protocol.payload.AsrSpeechActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.IrResultPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;

import java.time.Duration;
import java.util.EnumSet;

public final class IaProtocolAdapter extends AbstractProtocolAdapter implements DialogueProtocolPort {
    public static final String MODULE_ID = "module.ia";
    public static final String SOURCE_ID = "module.ia";

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

    public void registerLlmUsageAuthorizationCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.DIALOGUE_LLM_USAGE_AUTHORIZE,
                PayloadType.DIALOGUE_LLM_USAGE_AUTHORIZATION_REQUEST,
                DialogueLlmUsageAuthorizationRequestPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.REQUEST),
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

    public TianshuEnvelope respondLlmUsageAuthorizationResult(TianshuEnvelope parent, DialogueLlmUsageAuthorizationResultPayload payload) {
        return respondTo(parent, PayloadType.DIALOGUE_LLM_USAGE_AUTHORIZATION_RESULT, payload);
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

    @Override
    public TianshuEnvelope deliverToCapability(TianshuEnvelope parent, String capabilityId, DialogueDeliveryPayload payload) {
        return commandCapability(parent, capabilityId, PayloadType.DIALOGUE_DELIVERY, payload);
    }
}
