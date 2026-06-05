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
import com.rheinmetal.tianshu.protocol.payload.LlmTaskRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.StreamTextPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.EnumSet;

public final class AXProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = AXModule.MODULE_ID;
    public static final String SOURCE_ID = AXModule.MODULE_ID;
    public static final String DIALOGUE_DELIVERY_CAPABILITY = "AX.DIALOGUE_DELIVERY";
    public static final String STREAM_CHUNK_ROUTE = "AX.STREAM_CHUNK";

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

    public void registerLlmTaskResultRoute(EnvelopeHandler handler) {
        registerDirectRoute(
                MODULE_ID,
                PayloadType.LLM_TASK_RESULT,
                com.rheinmetal.tianshu.protocol.payload.LlmTaskResultPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public void registerLlmTaskStreamChunkRoute(EnvelopeHandler handler) {
        registerDirectRoute(
                MODULE_ID,
                PayloadType.LLM_TASK_STREAM_CHUNK,
                com.rheinmetal.tianshu.protocol.payload.LlmTaskStreamChunkPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public TianshuEnvelope requestLlm(LlmTaskRequestPayload payload) {
        return requestCapability(ProtocolCapabilities.LLM_TASK_REQUEST, PayloadType.LLM_TASK_REQUEST, payload);
    }

    public TianshuEnvelope requestLlm(TianshuEnvelope parent, LlmTaskRequestPayload payload) {
        return requestCapability(parent, ProtocolCapabilities.LLM_TASK_REQUEST, PayloadType.LLM_TASK_REQUEST, payload);
    }

    public TianshuEnvelope publishStreamChunk(TianshuEnvelope parent, StreamTextPayload payload) {
        return respondTo(parent, PayloadType.LLM_TEXT_CHUNK, payload);
    }

    public TianshuEnvelope publishStreamEnd(TianshuEnvelope parent, int index) {
        return respondTo(parent, PayloadType.LLM_TEXT_CHUNK, new StreamTextPayload("", index, true));
    }
}
