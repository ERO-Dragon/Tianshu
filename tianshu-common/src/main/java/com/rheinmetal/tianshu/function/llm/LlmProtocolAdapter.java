package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.function.ia.payload.DialogueLlmUsageAuthorizationRequestPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueLlmUsageAuthorizationResultPayload;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.LlmRagPathRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmRagPathResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskStreamChunkPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;

import java.time.Duration;
import java.util.EnumSet;

public final class LlmProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "module.llm";
    public static final String SOURCE_ID = "module.llm";

    public LlmProtocolAdapter(ProtocolRuntime runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard().withThreadPolicy(ThreadPolicy.IO_BLOCKING).withSupportsStreaming(true));
    }

    public void registerTaskRequestCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.LLM_TASK_REQUEST,
                PayloadType.LLM_TASK_REQUEST,
                LlmTaskRequestPayload.class,
                BrokerType.PARALLEL_LIMIT,
                EnumSet.of(PacketType.REQUEST, PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void registerRagPathResolveCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.LLM_RAG_PATH_RESOLVE,
                PayloadType.LLM_RAG_PATH_REQUEST,
                LlmRagPathRequestPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.REQUEST),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public TianshuEnvelope respondRagPathResult(TianshuEnvelope parent, LlmRagPathResultPayload payload) {
        return respondTo(parent, PayloadType.LLM_RAG_PATH_RESULT, payload);
    }

    public void registerLlmUsageAuthorizationResultRoute(EnvelopeHandler handler) {
        registerDirectRoute(
                MODULE_ID,
                PayloadType.DIALOGUE_LLM_USAGE_AUTHORIZATION_RESULT,
                DialogueLlmUsageAuthorizationResultPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public TianshuEnvelope requestLlmUsageAuthorization(TianshuEnvelope parent, DialogueLlmUsageAuthorizationRequestPayload payload) {
        return requestCapability(parent, ProtocolCapabilities.DIALOGUE_LLM_USAGE_AUTHORIZE, PayloadType.DIALOGUE_LLM_USAGE_AUTHORIZATION_REQUEST, payload);
    }

    public TianshuEnvelope publishTaskStreamChunk(TianshuEnvelope parent, LlmTaskStreamChunkPayload payload) {
        return respondTo(parent, PayloadType.LLM_TASK_STREAM_CHUNK, payload);
    }

    public TianshuEnvelope publishTaskStreamEnd(TianshuEnvelope parent, LlmTaskStreamChunkPayload payload) {
        return respondTo(parent, PayloadType.LLM_TASK_STREAM_CHUNK, payload);
    }

    public TianshuEnvelope respondTaskResult(TianshuEnvelope parent, LlmTaskResultPayload payload) {
        return respondTo(parent, PayloadType.LLM_TASK_RESULT, payload);
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

    public ProtocolTaskHandle scheduleAuthorizationTimeout(String taskId, Runnable task, long delayMillis) {
        return runtime().executors().schedule(
                taskSpec(ExecutionLane.SCHEDULED)
                        .taskId(taskId)
                        .concurrencyKey(MODULE_ID + ":authorization-timeout")
                        .maxConcurrency(1)
                        .queueCapacity(64)
                        .build(),
                task,
                Duration.ofMillis(Math.max(0L, delayMillis))
        );
    }
}
