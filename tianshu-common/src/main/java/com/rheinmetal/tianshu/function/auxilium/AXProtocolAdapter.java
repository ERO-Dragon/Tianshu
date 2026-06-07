package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.ia.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.EnumSet;

public final class AXProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = AXModule.MODULE_ID;
    public static final String SOURCE_ID = AXModule.MODULE_ID;
    public static final String DIALOGUE_DELIVERY_CAPABILITY = "AX.DIALOGUE_DELIVERY";

    public AXProtocolAdapter(ProtocolRuntime runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard().withSupportsStreaming(true));
    }

    public void registerDialogueDeliveryCapability(EnvelopeHandler handler) {
        registerCapability(
                DIALOGUE_DELIVERY_CAPABILITY,
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

    public void registerLlmPromptResultRoute(EnvelopeHandler handler) {
        registerDirectRoute(
                MODULE_ID,
                PayloadType.LLM_PROMPT_RESULT,
                com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public void registerLlmPromptStreamChunkRoute(EnvelopeHandler handler) {
        registerDirectRoute(
                MODULE_ID,
                PayloadType.LLM_PROMPT_STREAM_CHUNK,
                com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public TianshuEnvelope requestLlm(LLMPromptRequestPayload payload) {
        return requestCapability(ProtocolCapabilities.LLM_REQUEST, PayloadType.LLM_PROMPT_REQUEST, payload);
    }

    public TianshuEnvelope requestLlm(TianshuEnvelope parent, LLMPromptRequestPayload payload) {
        return requestCapability(parent, ProtocolCapabilities.LLM_REQUEST, PayloadType.LLM_PROMPT_REQUEST, payload);
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
