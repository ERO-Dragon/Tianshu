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
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

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

    public TianshuEnvelope commandSessionControl(TianshuEnvelope parent, com.rheinmetal.tianshu.function.ia.payload.DialogueSessionControlPayload payload) {
        return commandCapability(parent, ProtocolCapabilities.DIALOGUE_SESSION_CONTROL, PayloadType.DIALOGUE_SESSION_CONTROL, payload);
    }
}
