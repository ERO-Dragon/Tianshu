package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.contract.DialogueContextInputPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueParticipantUnregisterPayload;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.function.weaving.contract.LLMCacheManagePayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMCacheManageResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.protocol.payload.ModuleStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceChatMessagePayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityType;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.EnumSet;
import java.time.Duration;

public final class AXProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = AXModule.MODULE_ID;
    public static final String SOURCE_ID = ProtocolModuleIds.AX;
    public static final String DIALOGUE_INPUT_CAPABILITY = ProtocolCapabilities.AX_DIALOGUE_INPUT;
    public static final String CONTEXT_INPUT_CAPABILITY = ProtocolCapabilities.AX_CONTEXT_INPUT;
    private static final long CHAT_ACTIVITY_TTL_MILLIS = 180_000L;
    private static final long MAINTENANCE_ACTIVITY_TTL_MILLIS = 900_000L;
    /** Delegation backlog limit (design appendix A.2: broadcast queue limit 3, same order of magnitude). */
    private static final int DELEGATE_RUN_QUEUE_CAPACITY = 3;

    public AXProtocolAdapter(ModuleRuntimeAccess runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard().withSupportsStreaming(true));
    }

    /**
     * The runtime this adapter speaks through.
     *
     * <p>Collaborators in this module that must submit on the same lane (the speech bridge) ask here
     * instead of holding a second reference, so the two cannot drift apart.
     */
    public ModuleRuntimeAccess runtimeAccess() {
        return runtime();
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

    /**
     * 上下文型输入入口。外部模块直接投递“只需要被记住”的话语，不经过 IA，也不触发回答。
     */
    public void registerContextInputCapability(EnvelopeHandler handler) {
        registerCapability(
                CONTEXT_INPUT_CAPABILITY,
                PayloadType.DIALOGUE_CONTEXT_INPUT,
                DialogueContextInputPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
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

    /**
     * Submits one main-dialogue agent run to the {@code LONG} lane (design §12.1).
     *
     * <p>This is the only place a turn is allowed to block waiting for its LLM envelope: it occupies a
     * dedicated long-running thread, never the protocol {@code CPU} lane the delivery callback runs on,
     * and never the main/render thread.</p>
     *
     * <p>The concurrency key is {@code :agent}, deliberately distinct from the {@code :memory} key used
     * by compression and context maintenance — otherwise one 120s compression would queue the player's
     * sentence behind it. {@code maxConcurrency 1} keeps at most one main-dialogue run alive at a time.</p>
     *
     * <p>Lane is not payload: the LLM envelope itself stays on the {@code CHAT} lane. {@code LONG} only
     * says where <em>our own</em> waiting thread lives, so the main dialogue is not moved to {@code TASK}.</p>
     *
     * @throws java.util.concurrent.RejectedExecutionException when the lane refuses the task, so the
     *         caller retires the turn instead of letting it wait for an envelope that was never sent
     */
    public ProtocolTaskHandle submitAgentTurn(String taskId, Runnable task) {
        ProtocolTaskHandle handle = submitTask(taskSpec(ExecutionLane.LONG)
                .taskId(taskId)
                .concurrencyKey(MODULE_ID + ":agent")
                .maxConcurrency(1)
                .queueCapacity(4)
                .build(), task);
        if (handle != null && handle.state() == ProtocolTaskState.REJECTED) {
            String cause = handle.failureCause().map(Throwable::getMessage).orElse("");
            throw new java.util.concurrent.RejectedExecutionException(cause);
        }
        return handle;
    }

    /**
     * Submits one async delegation run to the {@code LONG} lane (design §5.1 step ④).
     *
     * <p>Four separate queue dimensions from the main dialogue: its own concurrency key
     * {@code :agent_delegate}, {@code maxConcurrency 1} and a small backlog — so a 120s retrieval can never
     * queue the player's next sentence behind it, and two delegations cannot interleave. The backlog is
     * capped at {@link #DELEGATE_RUN_QUEUE_CAPACITY} (design appendix A.2: queue limit 3).</p>
     *
     * <p>The payload lane of the Complex envelope is {@code TASK} (design §3.2). {@code LONG} only says
     * where <em>our own</em> waiting thread lives.</p>
     *
     * @throws java.util.concurrent.RejectedExecutionException when the lane refuses the task, so the caller
     *         can write the {@code FAILED(rejected)} terminal instead of leaving the ticket pending
     */
    public ProtocolTaskHandle submitDelegateRun(String taskId, Runnable task) {
        ProtocolTaskHandle handle = submitTask(taskSpec(ExecutionLane.LONG)
                .taskId(taskId)
                .concurrencyKey(MODULE_ID + ":agent_delegate")
                .maxConcurrency(1)
                .queueCapacity(DELEGATE_RUN_QUEUE_CAPACITY)
                .build(), task);
        if (handle != null && handle.state() == ProtocolTaskState.REJECTED) {
            String cause = handle.failureCause().map(Throwable::getMessage).orElse("");
            throw new java.util.concurrent.RejectedExecutionException(cause);
        }
        return handle;
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

    public TianshuEnvelope publishPresenceActivity(PresenceActivityPayload payload) {
        return publishTopic(ProtocolTopics.PRESENCE_ACTIVITY, PayloadType.PRESENCE_ACTIVITY, payload);
    }

    public TianshuEnvelope publishChatActivity(String activityId, PresenceActivityType type, boolean active) {
        if (type != PresenceActivityType.THINKING && type != PresenceActivityType.RESPONDING) {
            throw new IllegalArgumentException("AX chat activity must be THINKING or RESPONDING");
        }
        return publishPresenceActivity(active
                ? PresenceActivityPayload.started(activityId, type, CHAT_ACTIVITY_TTL_MILLIS)
                : PresenceActivityPayload.ended(activityId, type));
    }

    public TianshuEnvelope publishMaintenanceActivity(String activityId, boolean active) {
        return publishPresenceActivity(active
                ? PresenceActivityPayload.started(activityId, PresenceActivityType.PROCESSING_TASK, MAINTENANCE_ACTIVITY_TTL_MILLIS)
                : PresenceActivityPayload.ended(activityId, PresenceActivityType.PROCESSING_TASK));
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

    public TianshuEnvelope commandSessionControl(TianshuEnvelope parent, com.rheinmetal.tianshu.function.ia.contract.DialogueSessionControlPayload payload) {
        return commandCapability(parent, ProtocolCapabilities.DIALOGUE_SESSION_CONTROL, PayloadType.DIALOGUE_SESSION_CONTROL, payload);
    }
}
