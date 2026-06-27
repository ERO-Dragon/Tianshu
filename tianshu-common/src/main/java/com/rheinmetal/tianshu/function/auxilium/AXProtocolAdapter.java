package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.ia.payload.DialogueDeliveryPayload;
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
import com.rheinmetal.tianshu.protocol.payload.LLMCacheManagePayload;
import com.rheinmetal.tianshu.protocol.payload.LLMCacheManageResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsControlPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;

import java.util.EnumSet;

public final class AXProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = AXModule.MODULE_ID;
    public static final String SOURCE_ID = AXModule.MODULE_ID;
    public static final String DIALOGUE_INPUT_CAPABILITY = "AX.DIALOGUE_INPUT";

    public AXProtocolAdapter(ProtocolRuntime runtime) {
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

    public TianshuEnvelope buildLlmRequest(LLMPromptRequestPayload payload) {
        return buildRequestCapability(ProtocolCapabilities.LLM_REQUEST, PayloadType.LLM_PROMPT_REQUEST, payload);
    }

    public TianshuEnvelope buildLlmRequest(TianshuEnvelope parent, LLMPromptRequestPayload payload) {
        return buildRequestCapability(parent, ProtocolCapabilities.LLM_REQUEST, PayloadType.LLM_PROMPT_REQUEST, payload);
    }

    public TianshuEnvelope submitLlmRequest(TianshuEnvelope envelope) {
        return submitPrepared(envelope);
    }

    public ProtocolTaskHandle submitAxTask(String taskId, ExecutionLane lane, Runnable task) {
        return submitTask(taskSpec(lane == null ? ExecutionLane.LONG : lane)
                .taskId(taskId)
                .concurrencyKey(MODULE_ID + ":memory")
                .maxConcurrency(1)
                .queueCapacity(16)
                .build(), task);
    }

    public TianshuEnvelope buildPresenceContextQuery(TianshuEnvelope parent, PresenceContextQueryPayload payload) {
        return buildRequestCapability(parent, ProtocolCapabilities.PRESENCE_QUERY_CONTEXT, PayloadType.PRESENCE_CONTEXT_QUERY, payload);
    }

    public TianshuEnvelope submitPresenceContextQuery(TianshuEnvelope envelope) {
        return submitPrepared(envelope);
    }

    public int presenceContextProviderCount() {
        return runtime().capabilities().findCapability(ProtocolCapabilities.PRESENCE_QUERY_CONTEXT).size();
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
        return runtime().capabilities().findCapability(ProtocolCapabilities.LLM_PRIMITIVE_QUERY).size();
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

    public TianshuEnvelope speakTts(TtsSpeakPayload payload) {
        return commandCapability(ProtocolCapabilities.TTS_SPEAK, PayloadType.TTS_TEXT, payload);
    }

    public TianshuEnvelope speakTts(TianshuEnvelope parent, TtsSpeakPayload payload) {
        return commandCapability(parent, ProtocolCapabilities.TTS_SPEAK, PayloadType.TTS_TEXT, payload);
    }

    public TianshuEnvelope controlTts(TtsControlPayload payload) {
        return commandCapability(ProtocolCapabilities.TTS_CONTROL, PayloadType.CUSTOM, payload);
    }

    public TianshuEnvelope commandSessionControl(TianshuEnvelope parent, com.rheinmetal.tianshu.function.ia.payload.DialogueSessionControlPayload payload) {
        return commandCapability(parent, ProtocolCapabilities.DIALOGUE_SESSION_CONTROL, PayloadType.DIALOGUE_SESSION_CONTROL, payload);
    }
}
