package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueParticipantUnregisterPayload;
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
import com.rheinmetal.tianshu.protocol.payload.LLMCacheManagePayload;
import com.rheinmetal.tianshu.protocol.payload.LLMCacheManageResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.protocol.payload.ModuleStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceChatMessagePayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsControlPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.EnumSet;
import java.time.Duration;

public final class AXProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = AXModule.MODULE_ID;
    public static final String SOURCE_ID = AXModule.MODULE_ID;
    public static final String DIALOGUE_INPUT_CAPABILITY = "AX.DIALOGUE_INPUT";

    public AXProtocolAdapter(ModuleRuntimeAccess runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard().withSupportsStreaming(true));
    }

    public void registerDialogueInputCapability(EnvelopeHandler handler) {
        registerCapability(
                DIALOGUE_INPUT_CAPABILITY,
                PayloadType.DIALOGUE_DELIVERY,
                DialogueDeliveryPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void subscribePresenceWorldEvents(EnvelopeHandler handler) {
        subscribeTopic(
                PresenceWorldEventPayload.TOPIC,
                PayloadType.CUSTOM,
                PresenceWorldEventPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.EVENT),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public void subscribePresenceChatMessages(EnvelopeHandler handler) {
        subscribeTopic(
                PresenceChatMessagePayload.TOPIC,
                PayloadType.CUSTOM,
                PresenceChatMessagePayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.EVENT),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public TianshuEnvelope buildLlmRequest(LLMPromptRequestPayload payload) {
        return buildRequestCapability(ProtocolCapabilities.LLM_REQUEST, PayloadType.LLM_PROMPT_REQUEST, payload);
    }

    public TianshuEnvelope buildLlmRequest(TianshuEnvelope parent, LLMPromptRequestPayload payload) {
        return buildRequestCapability(parent, ProtocolCapabilities.LLM_REQUEST, PayloadType.LLM_PROMPT_REQUEST, payload);
    }

    public TianshuEnvelope submitLlmRequest(TianshuEnvelope envelope) {
        return submitPrepared(envelope);
    }

    public TianshuEnvelope cancelLlmRequest(TianshuEnvelope envelope, String reasonCode, String message) {
        return cancelEnvelope(envelope, reasonCode, message);
    }

    public ProtocolTaskHandle submitAxTask(String taskId, ExecutionLane lane, Runnable task) {
        return submitTask(taskSpec(lane == null ? ExecutionLane.LONG : lane)
                .taskId(taskId)
                .concurrencyKey(MODULE_ID + ":memory")
                .maxConcurrency(1)
                .queueCapacity(16)
                .build(), task);
    }

    public ProtocolTaskHandle scheduleTimeout(String taskId, Runnable task, Duration delay) {
        return runtime().schedule(taskSpec(ExecutionLane.SCHEDULED)
                .taskId(taskId)
                .concurrencyKey(MODULE_ID + ":timeouts")
                .maxConcurrency(1)
                .queueCapacity(64)
                .build(), task, delay);
    }

    public TianshuEnvelope buildPresenceContextQuery(TianshuEnvelope parent, PresenceContextQueryPayload payload) {
        return buildRequestCapability(parent, ProtocolCapabilities.PRESENCE_QUERY_CONTEXT, PayloadType.PRESENCE_CONTEXT_QUERY, payload);
    }

    public TianshuEnvelope submitPresenceContextQuery(TianshuEnvelope envelope) {
        return submitPrepared(envelope);
    }

    public TianshuEnvelope cancelPresenceContextQuery(TianshuEnvelope envelope, String reasonCode, String message) {
        return cancelEnvelope(envelope, reasonCode, message);
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

    public TianshuEnvelope buildLlmPrimitiveQuery(LLMPrimitiveQueryPayload payload) {
        return buildRequestCapability(ProtocolCapabilities.LLM_PRIMITIVE_QUERY, PayloadType.LLM_PRIMITIVE_QUERY, payload);
    }

    public TianshuEnvelope buildLlmPrimitiveQuery(TianshuEnvelope parent, LLMPrimitiveQueryPayload payload) {
        return buildRequestCapability(parent, ProtocolCapabilities.LLM_PRIMITIVE_QUERY, PayloadType.LLM_PRIMITIVE_QUERY, payload);
    }

    public TianshuEnvelope submitLlmPrimitiveQuery(TianshuEnvelope envelope) {
        return submitPrepared(envelope);
    }

    public TianshuEnvelope cancelLlmPrimitiveQuery(TianshuEnvelope envelope, String reasonCode, String message) {
        return cancelEnvelope(envelope, reasonCode, message);
    }

    public void registerLlmPrimitiveResultResponse(String requestEnvelopeId, EnvelopeHandler handler) {
        registerResponseHandler(
                requestEnvelopeId,
                PayloadType.LLM_PRIMITIVE_RESULT,
                LLMPrimitiveResultPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void unregisterLlmPrimitiveResponses(String requestEnvelopeId) {
        unregisterResponseHandlers(requestEnvelopeId);
    }

    public int llmPrimitiveProviderCount() {
        return runtime().capabilityProviderCount(ProtocolCapabilities.LLM_PRIMITIVE_QUERY);
    }

    public TianshuEnvelope buildLlmCacheManage(LLMCacheManagePayload payload) {
        return buildRequestCapability(ProtocolCapabilities.LLM_CACHE_MANAGE, PayloadType.LLM_CACHE_MANAGE, payload);
    }

    public TianshuEnvelope buildLlmCacheManage(TianshuEnvelope parent, LLMCacheManagePayload payload) {
        return buildRequestCapability(parent, ProtocolCapabilities.LLM_CACHE_MANAGE, PayloadType.LLM_CACHE_MANAGE, payload);
    }

    public TianshuEnvelope submitLlmCacheManage(TianshuEnvelope envelope) {
        return submitPrepared(envelope);
    }

    public TianshuEnvelope cancelLlmCacheManage(TianshuEnvelope envelope, String reasonCode, String message) {
        return cancelEnvelope(envelope, reasonCode, message);
    }

    public void registerLlmCacheManageResultResponse(String requestEnvelopeId, EnvelopeHandler handler) {
        registerResponseHandler(
                requestEnvelopeId,
                PayloadType.LLM_CACHE_MANAGE_RESULT,
                LLMCacheManageResultPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void unregisterLlmCacheManageResponses(String requestEnvelopeId) {
        unregisterResponseHandlers(requestEnvelopeId);
    }

    public int llmCacheManageProviderCount() {
        return runtime().capabilityProviderCount(ProtocolCapabilities.LLM_CACHE_MANAGE);
    }

    public void registerLlmPromptResultResponse(String requestEnvelopeId, EnvelopeHandler handler) {
        registerResponseHandler(
                requestEnvelopeId,
                PayloadType.LLM_PROMPT_RESULT,
                LLMPromptResultPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void registerLlmPromptStreamChunkResponse(String requestEnvelopeId, EnvelopeHandler handler) {
        registerResponseHandler(
                requestEnvelopeId,
                PayloadType.LLM_PROMPT_STREAM_CHUNK,
                LLMPromptStreamChunkPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void unregisterLlmResponses(String requestEnvelopeId) {
        unregisterResponseHandlers(requestEnvelopeId);
    }

    public TianshuEnvelope publishModuleStatus(ModuleStatus status) {
        if (status == null) {
            return null;
        }
        return publishTopic(ProtocolTopics.MODULE_STATUS, PayloadType.MODULE_STATUS, new ModuleStatusPayload(status));
    }

    public int dialogueParticipantRegistrationProviderCount() {
        return runtime().capabilityProviderCount(ProtocolCapabilities.DIALOGUE_PARTICIPANT_REGISTER);
    }

    public TianshuEnvelope registerDialogueParticipant(DialogueParticipantRegisterPayload payload) {
        return commandCapability(ProtocolCapabilities.DIALOGUE_PARTICIPANT_REGISTER, PayloadType.DIALOGUE_PARTICIPANT_REGISTER, payload);
    }

    public TianshuEnvelope unregisterDialogueParticipant(DialogueParticipantUnregisterPayload payload) {
        return commandCapability(ProtocolCapabilities.DIALOGUE_PARTICIPANT_UNREGISTER, PayloadType.DIALOGUE_PARTICIPANT_UNREGISTER, payload);
    }

    public TianshuEnvelope speakTts(TtsSpeakPayload payload) {
        return commandCapability(ProtocolCapabilities.TTS_SPEAK, PayloadType.TTS_TEXT, payload);
    }

    public TianshuEnvelope speakTts(TianshuEnvelope parent, TtsSpeakPayload payload) {
        return commandCapability(parent, ProtocolCapabilities.TTS_SPEAK, PayloadType.TTS_TEXT, payload);
    }

    public TianshuEnvelope streamTtsSentence(TianshuEnvelope parent, TtsSpeakPayload payload) {
        return parent == null
                ? submitToCapability(ProtocolCapabilities.TTS_SPEAK, PacketType.STREAM_CHUNK, PayloadType.TTS_TEXT, payload)
                : submitToCapability(parent, ProtocolCapabilities.TTS_SPEAK, PacketType.STREAM_CHUNK, PayloadType.TTS_TEXT, payload);
    }

    public TianshuEnvelope endTtsSession(TianshuEnvelope parent, TtsSpeakPayload payload) {
        return parent == null
                ? submitToCapability(ProtocolCapabilities.TTS_SPEAK, PacketType.STREAM_END, PayloadType.TTS_TEXT, payload)
                : submitToCapability(parent, ProtocolCapabilities.TTS_SPEAK, PacketType.STREAM_END, PayloadType.TTS_TEXT, payload);
    }

    public TianshuEnvelope stopOwnTtsOutput(String reasonCode) {
        return commandCapability(
                ProtocolCapabilities.TTS_CONTROL,
                PayloadType.CUSTOM,
                new TtsControlPayload(
                        TtsControlPayload.Action.STOP_SOURCE,
                        "",
                        SOURCE_ID,
                        reasonCode == null ? "" : reasonCode
                )
        );
    }

    public TianshuEnvelope commandSessionControl(TianshuEnvelope parent, com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueSessionControlPayload payload) {
        return commandCapability(parent, ProtocolCapabilities.DIALOGUE_SESSION_CONTROL, PayloadType.DIALOGUE_SESSION_CONTROL, payload);
    }
}
