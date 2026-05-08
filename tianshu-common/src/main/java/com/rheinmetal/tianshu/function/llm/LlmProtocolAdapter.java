package com.rheinmetal.tianshu.function.llm;

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
import com.rheinmetal.tianshu.protocol.payload.LlmCommandRepairPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmCommandRepairResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmIntentClassifyPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmIntentClassifyResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmPromptPayload;
import com.rheinmetal.tianshu.protocol.payload.StreamTextPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;

import java.util.EnumSet;

public final class LlmProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "module.llm";
    public static final String SOURCE_ID = "module.llm";

    public LlmProtocolAdapter(ProtocolRuntime runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard().withThreadPolicy(ThreadPolicy.IO_BLOCKING).withSupportsStreaming(true));
    }

    public void registerChatCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.LLM_CHAT,
                PayloadType.LLM_PROMPT,
                LlmPromptPayload.class,
                BrokerType.PARALLEL_LIMIT,
                EnumSet.of(PacketType.REQUEST, PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void registerFeedbackCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.LLM_FEEDBACK,
                PayloadType.LLM_PROMPT,
                LlmPromptPayload.class,
                BrokerType.PARALLEL_LIMIT,
                EnumSet.of(PacketType.REQUEST, PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void registerIntentClassifyCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.LLM_INTENT_CLASSIFY,
                PayloadType.LLM_INTENT_CLASSIFY,
                LlmIntentClassifyPayload.class,
                BrokerType.PARALLEL_LIMIT,
                EnumSet.of(PacketType.REQUEST, PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void registerCommandRepairCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.LLM_COMMAND_REPAIR,
                PayloadType.LLM_COMMAND_REPAIR,
                LlmCommandRepairPayload.class,
                BrokerType.PARALLEL_LIMIT,
                EnumSet.of(PacketType.REQUEST, PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public TianshuEnvelope publishIntentClassifyResult(TianshuEnvelope parent, LlmIntentClassifyResultPayload payload) {
        return submitToTopic(parent, ProtocolTopics.LLM_INTENT_CLASSIFY_RESULT, PacketType.EVENT, PayloadType.LLM_INTENT_CLASSIFY, payload);
    }

    public TianshuEnvelope publishCommandRepairResult(TianshuEnvelope parent, LlmCommandRepairResultPayload payload) {
        return submitToTopic(parent, ProtocolTopics.LLM_COMMAND_REPAIR_RESULT, PacketType.EVENT, PayloadType.LLM_COMMAND_REPAIR, payload);
    }

    public TianshuEnvelope publishStreamChunk(TianshuEnvelope parent, StreamTextPayload payload) {
        return submitToTopic(parent, ProtocolTopics.LLM_STREAM, PacketType.STREAM_CHUNK, PayloadType.LLM_TEXT_CHUNK, payload);
    }

    public TianshuEnvelope publishStreamEnd(TianshuEnvelope parent, int index) {
        return submitToTopic(parent, ProtocolTopics.LLM_STREAM, PacketType.STREAM_END, PayloadType.LLM_TEXT_CHUNK, new StreamTextPayload("", index, true));
    }

    public ProtocolTaskHandle submitLlmIoTask(String envelopeId, Runnable task) {
        return submitTask(
                taskSpec(ExecutionLane.IO)
                        .envelopeId(envelopeId)
                        .concurrencyKey(MODULE_ID + ":stream")
                        .maxConcurrency(1)
                        .queueCapacity(4)
                        .build(),
                task
        );
    }
}
