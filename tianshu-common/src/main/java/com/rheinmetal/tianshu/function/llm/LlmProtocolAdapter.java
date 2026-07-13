package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.function.llm.service.Chunk;
import com.rheinmetal.tianshu.function.llm.service.LlmInferencePolicy;
import com.rheinmetal.tianshu.function.llm.service.LLMRequest;
import com.rheinmetal.tianshu.function.llm.service.LLMService;
import com.rheinmetal.tianshu.function.llm.service.MessageItem;
import com.rheinmetal.tianshu.function.llm.service.RagCacheManager;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueLlmUsageAuthorizationRequestPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueLlmUsageAuthorizationResultPayload;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.LLMCacheManagePayload;
import com.rheinmetal.tianshu.protocol.payload.LLMCacheManageResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMRuntimeSnapshotPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.protocol.payload.ModuleStatusPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;

public final class LlmProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "module.llm";
    public static final String SOURCE_ID = "module.llm";

    private volatile LLMService llmService;
    private final LlmTaskAdmissionController taskAdmissionController;

    public LlmProtocolAdapter(ProtocolRuntime runtime, LLMService llmService) {
        this(runtime, llmService, new LlmTaskAdmissionController(0));
    }

    public LlmProtocolAdapter(ProtocolRuntime runtime, LLMService llmService, LlmTaskAdmissionController taskAdmissionController) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard().withThreadPolicy(ThreadPolicy.IO_BLOCKING).withSupportsStreaming(true));
        this.llmService = llmService;
        this.taskAdmissionController = taskAdmissionController == null ? new LlmTaskAdmissionController(0) : taskAdmissionController;
    }

    public void setLlmService(LLMService llmService) {
        this.llmService = llmService;
        if (llmService == null) {
            taskAdmissionController.clearWaitingTasks("LLM_SERVICE_NOT_READY", "LLM service is not initialized");
        }
    }

    public void registerLLMRequestCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.LLM_REQUEST,
                PayloadType.LLM_PROMPT_REQUEST,
                LLMPromptRequestPayload.class,
                BrokerType.PARALLEL_LIMIT,
                EnumSet.of(PacketType.REQUEST, PacketType.COMMAND),
                Priority.NORMAL,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void registerLLMCacheManageCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.LLM_CACHE_MANAGE,
                PayloadType.LLM_CACHE_MANAGE,
                LLMCacheManagePayload.class,
                BrokerType.PARALLEL_LIMIT,
                EnumSet.of(PacketType.REQUEST, PacketType.COMMAND),
                Priority.NORMAL,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void registerLLMPrimitiveQueryCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.LLM_PRIMITIVE_QUERY,
                PayloadType.LLM_PRIMITIVE_QUERY,
                LLMPrimitiveQueryPayload.class,
                BrokerType.PARALLEL_LIMIT,
                EnumSet.of(PacketType.REQUEST, PacketType.COMMAND),
                Priority.NORMAL,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public TianshuEnvelope requestLLMPrimitiveQuery(LLMPrimitiveQueryPayload payload) {
        return requestCapability(ProtocolCapabilities.LLM_PRIMITIVE_QUERY, PayloadType.LLM_PRIMITIVE_QUERY, payload);
    }

    public TianshuEnvelope requestLLMPrimitiveQuery(TianshuEnvelope parent, LLMPrimitiveQueryPayload payload) {
        return requestCapability(parent, ProtocolCapabilities.LLM_PRIMITIVE_QUERY, PayloadType.LLM_PRIMITIVE_QUERY, payload);
    }

    public void registerLLMPrimitiveResultResponse(String requestEnvelopeId, EnvelopeHandler handler) {
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

    public TianshuEnvelope respondLLMPrimitiveResult(TianshuEnvelope parent, LLMPrimitiveResultPayload payload) {
        return respondTo(parent, PayloadType.LLM_PRIMITIVE_RESULT, payload);
    }

    public TianshuEnvelope requestLLM(LLMPromptRequestPayload payload) {
        return requestCapability(ProtocolCapabilities.LLM_REQUEST, PayloadType.LLM_PROMPT_REQUEST, payload);
    }

    public TianshuEnvelope requestLLM(TianshuEnvelope parent, LLMPromptRequestPayload payload) {
        return requestCapability(parent, ProtocolCapabilities.LLM_REQUEST, PayloadType.LLM_PROMPT_REQUEST, payload);
    }

    public TianshuEnvelope respondLLMPromptResult(TianshuEnvelope parent, LLMPromptResultPayload payload) {
        return respondTo(parent, PayloadType.LLM_PROMPT_RESULT, payload);
    }

    public TianshuEnvelope publishLLMPromptStreamChunk(TianshuEnvelope parent, LLMPromptStreamChunkPayload payload) {
        return respondTo(parent, PayloadType.LLM_PROMPT_STREAM_CHUNK, payload);
    }

    public TianshuEnvelope publishLLMPromptStreamEnd(TianshuEnvelope parent, int index) {
        return publishLLMPromptStreamEnd(parent, index, "COMPLETED", LLMPromptResultPayload.TokenUsagePayload.empty(), null);
    }

    public TianshuEnvelope publishLLMPromptStreamEnd(TianshuEnvelope parent, int index, String finishType, LLMPromptResultPayload.TokenUsagePayload usage, String errorMessage) {
        return publishLLMPromptStreamEnd(parent, index, finishType, usage, errorMessage, "");
    }

    public TianshuEnvelope publishLLMPromptStreamEnd(TianshuEnvelope parent, int index, String finishType, LLMPromptResultPayload.TokenUsagePayload usage, String errorMessage, String thinkingContent) {
        LLMPromptStreamChunkPayload endPayload = LLMPromptStreamChunkPayload.end(
                streamRequestId(parent),
                index,
                finishType,
                usage,
                errorMessage,
                thinkingContent
        );
        return respondTo(parent, PayloadType.LLM_PROMPT_STREAM_CHUNK, endPayload);
    }

    public TianshuEnvelope publishInferenceStatus(LlmStatusPayload status) {
        if (status == null) {
            return null;
        }
        if (runtime().topicSubscriptions().findTopic(ProtocolTopics.LLM_STATUS).isEmpty()) {
            return null;
        }
        return publishTopic(ProtocolTopics.LLM_STATUS, PayloadType.LLM_STATUS, status);
    }

    public TianshuEnvelope publishModuleStatus(ModuleStatus status) {
        if (status == null) {
            return null;
        }
        return publishTopic(ProtocolTopics.MODULE_STATUS, PayloadType.MODULE_STATUS, new ModuleStatusPayload(status));
    }

    public TianshuEnvelope buildDialogueLlmUsageAuthorizationRequest(TianshuEnvelope parent, DialogueLlmUsageAuthorizationRequestPayload payload) {
        return buildRequestCapability(parent, ProtocolCapabilities.DIALOGUE_LLM_USAGE_AUTHORIZE, PayloadType.DIALOGUE_LLM_USAGE_AUTHORIZATION_REQUEST, payload);
    }

    public TianshuEnvelope submitDialogueLlmUsageAuthorizationRequest(TianshuEnvelope envelope) {
        return submitPrepared(envelope);
    }

    public void registerDialogueLlmUsageAuthorizationResponse(String requestEnvelopeId, EnvelopeHandler handler) {
        registerResponseHandler(
                requestEnvelopeId,
                PayloadType.DIALOGUE_LLM_USAGE_AUTHORIZATION_RESULT,
                DialogueLlmUsageAuthorizationResultPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void handleLLMRequest(TianshuEnvelope envelope) {
        handleLLMRequest(envelope, null);
    }

    public void handleLLMRequest(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope == null || !(envelope.payload() instanceof LLMPromptRequestPayload payload)) {
            complete(context, envelope);
            return;
        }
        if (llmService == null) {
            respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                    payload.requestId(),
                    "LLM_SERVICE_NOT_READY",
                    "LLM service is not initialized"
            ));
            fail(context, envelope, "LLM_SERVICE_NOT_READY", "LLM service is not initialized", null);
            return;
        }

        if (requiresDialogueAuthorization(payload)) {
            authorizeDialogueChatThenHandle(envelope, payload, context);
            return;
        }

        handleAuthorizedLLMRequest(envelope, payload, context);
    }

    private void handleAuthorizedLLMRequest(TianshuEnvelope envelope, LLMPromptRequestPayload payload, ProtocolContext context) {
        try {
            LLMRequest request = toLLMRequest(payload);
            boolean isStream = Boolean.TRUE.equals(payload.stream());
            boolean isTask = "TASK".equalsIgnoreCase(payload.lane());

            if (isTask && isStream) {
                admitTaskRequest(envelope, request, payload, context, true);
            } else if (isTask) {
                admitTaskRequest(envelope, request, payload, context, false);
            } else if (isStream) {
                if (handleStreamRequest(envelope, request, payload)) {
                    complete(context, envelope);
                } else {
                    fail(context, envelope, "LLM_INFERENCE_FAILED", "LLM stream chat failed", null);
                }
            } else {
                handleChatRequest(envelope, request, payload, context);
            }
        } catch (Exception e) {
            respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                    payload.requestId(),
                    "LLM_REQUEST_FAILED",
                    e.getMessage()
            ));
            fail(context, envelope, "LLM_REQUEST_FAILED", e.getMessage(), e);
        }
    }

    private void admitTaskRequest(TianshuEnvelope envelope, LLMRequest request, LLMPromptRequestPayload payload, ProtocolContext context, boolean stream) {
        taskAdmissionController.submit(
                payload.taskPriority(),
                payload.taskPreemptible(),
                () -> {
                    if (stream) {
                        return startTaskStreamRequest(envelope, request, payload, context);
                    }
                    return startTaskRequest(envelope, request, payload, context);
                },
                (code, message) -> rejectTask(envelope, payload, context, code, message)
        );
    }

    private void rejectTask(TianshuEnvelope envelope, LLMPromptRequestPayload payload, ProtocolContext context, String code, String message) {
        respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                payload.requestId(),
                code,
                message
        ));
        fail(context, envelope, code, message, null);
    }

    private boolean requiresDialogueAuthorization(LLMPromptRequestPayload payload) {
        return payload != null && "CHAT".equalsIgnoreCase(payload.lane());
    }

    private void authorizeDialogueChatThenHandle(TianshuEnvelope envelope, LLMPromptRequestPayload payload, ProtocolContext context) {
        if (!payload.hasDialogueAuthorizationContext()) {
            rejectUnauthorizedDialogueChat(envelope, payload, context, "DIALOGUE_AUTH_CONTEXT_MISSING", "CHAT LLM request requires dialogue session authorization context");
            return;
        }
        String requesterModuleId = envelope.header() == null ? "" : clean(envelope.header().sourceId());
        if (requesterModuleId.isBlank() || !requesterModuleId.equals(payload.requesterModuleId())) {
            rejectUnauthorizedDialogueChat(envelope, payload, context, "DIALOGUE_AUTH_REQUESTER_MISMATCH", "CHAT LLM requester module must match envelope source module");
            return;
        }
        if (runtime().capabilities().findCapability(ProtocolCapabilities.DIALOGUE_LLM_USAGE_AUTHORIZE).isEmpty()) {
            rejectUnauthorizedDialogueChat(envelope, payload, context, "DIALOGUE_AUTH_UNAVAILABLE", "Dialogue LLM authorization capability is not available");
            return;
        }

        DialogueLlmUsageAuthorizationRequestPayload authorizationPayload = new DialogueLlmUsageAuthorizationRequestPayload(
                payload.dialogueSessionId(),
                requesterModuleId,
                payload.requesterParticipantId(),
                payload.dialogueTurnId(),
                System.currentTimeMillis()
        );
        TianshuEnvelope authorizationEnvelope = buildDialogueLlmUsageAuthorizationRequest(envelope, authorizationPayload);
        registerDialogueLlmUsageAuthorizationResponse(authorizationEnvelope.envelopeId(), (responseEnvelope, responseContext) -> {
            try {
                if (!(responseEnvelope.payload() instanceof DialogueLlmUsageAuthorizationResultPayload result)) {
                    rejectUnauthorizedDialogueChat(envelope, payload, context, "DIALOGUE_AUTH_INVALID_RESPONSE", "Dialogue authorization response payload is invalid");
                    fail(responseContext, responseEnvelope, "INVALID_PAYLOAD", "Dialogue authorization response payload is invalid", null);
                    return;
                }
                if (!result.allowed()) {
                    String reason = result.reasonCode().isBlank() ? "DIALOGUE_AUTH_DENIED" : result.reasonCode();
                    String message = result.message().isBlank() ? "Dialogue LLM usage is not authorized" : result.message();
                    rejectUnauthorizedDialogueChat(envelope, payload, context, reason, message);
                    complete(responseContext, responseEnvelope);
                    return;
                }
                complete(responseContext, responseEnvelope);
                handleAuthorizedLLMRequest(envelope, payload, context);
            } finally {
                unregisterResponseHandlers(authorizationEnvelope.envelopeId());
            }
        });
        submitDialogueLlmUsageAuthorizationRequest(authorizationEnvelope);
    }

    private void rejectUnauthorizedDialogueChat(TianshuEnvelope envelope, LLMPromptRequestPayload payload, ProtocolContext context, String code, String message) {
        respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(payload.requestId(), code, message));
        fail(context, envelope, code, message, null);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private void handleChatRequest(TianshuEnvelope envelope, LLMRequest request, LLMPromptRequestPayload payload, ProtocolContext context) {
        try {
            LLMService.LLMResult result = llmService.chat(request);
            respondLLMPromptResult(envelope, LLMPromptResultPayload.completed(
                    payload.requestId(),
                    result.text(),
                    result.thinkingContent(),
                    result.ragHits(),
                    result.usage()
            ));
            complete(context, envelope);
        } catch (Exception e) {
            Throwable cause = unwrapCompletion(e);
            if (isTaskCancellation(cause)) {
                respondLLMPromptResult(envelope, LLMPromptResultPayload.cancelled(
                        payload.requestId(),
                        "",
                        List.of(),
                        LLMPromptResultPayload.TokenUsagePayload.empty()
                ));
                cancel(context, envelope, cancellationReasonCode(cause), failureMessage(cause));
                return;
            }
            respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                    payload.requestId(),
                    "LLM_INFERENCE_FAILED",
                    failureMessage(cause)
            ));
            fail(context, envelope, "LLM_INFERENCE_FAILED", failureMessage(cause), cause);
        }
    }

    private boolean handleStreamRequest(TianshuEnvelope envelope, LLMRequest request, LLMPromptRequestPayload payload) {
        StringBuilder visibleCollected = new StringBuilder();
        StringBuilder thinkingCollected = new StringBuilder();
        int[] index = {0};
        boolean[] ragHitsPublished = {false};
        List<LLMPromptResultPayload.RagHitPayload> ragHits = new ArrayList<>();

        try {
            LLMService.LLMStreamResult streamResult = llmService.chatStream(request, token -> {
                publishInitialRagHits(envelope, payload, ragHits, index, ragHitsPublished);
                if (token != null && !token.isEmpty()) {
                    visibleCollected.append(token);
                    LLMPromptStreamChunkPayload chunk = LLMPromptStreamChunkPayload.chunk(
                            payload.requestId(),
                            token,
                            index[0]++
                    );
                    publishLLMPromptStreamChunk(envelope, chunk);
                }
            }, thinking -> {
                if (thinking != null && !thinking.isEmpty()) {
                    thinkingCollected.append(thinking);
                    publishLLMPromptStreamChunk(envelope, LLMPromptStreamChunkPayload.thinking(
                            payload.requestId(),
                            thinking,
                            index[0]++
                    ));
                }
            }, ragHits);
            publishInitialRagHits(envelope, payload, ragHits, index, ragHitsPublished);

            LLMService.LLMStreamFinish finish = streamResult.finish();
            String thinkingContent = streamThinkingContent(thinkingCollected, finish.thinkingContent());
            publishLLMPromptStreamEnd(envelope, index[0], finish.type(), finish.usage(), failureMessage(finish.error()), thinkingContent);
            respondLLMPromptResult(envelope, LLMPromptResultPayload.completed(
                    payload.requestId(),
                    visibleCollected.length() > 0 ? visibleCollected.toString() : streamResult.text(),
                    thinkingContent,
                    ragHits,
                    finish.usage()
            ));
            return true;
        } catch (Exception e) {
            Throwable cause = unwrapCompletion(e);
            boolean cancelled = isTaskCancellation(cause);
            String finishType = cancelled ? "CANCELLED" : "FAILED";
            publishInitialRagHits(envelope, payload, ragHits, index, ragHitsPublished);
            publishLLMPromptStreamEnd(envelope, index[0], finishType, LLMPromptResultPayload.TokenUsagePayload.empty(), cancelled ? null : failureMessage(cause), thinkingCollected.toString());
            if (cancelled) {
                respondLLMPromptResult(envelope, LLMPromptResultPayload.cancelled(
                        payload.requestId(),
                        visibleCollected.toString(),
                        thinkingCollected.toString(),
                        ragHits,
                        LLMPromptResultPayload.TokenUsagePayload.empty()
                ));
                return true;
            }
            respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                    payload.requestId(),
                    "LLM_INFERENCE_FAILED",
                    failureMessage(cause),
                    visibleCollected.toString(),
                    thinkingCollected.toString(),
                    ragHits,
                    LLMPromptResultPayload.TokenUsagePayload.empty()
            ));
            return false;
        }
    }

    private CompletableFuture<Void> startTaskRequest(TianshuEnvelope envelope, LLMRequest request, LLMPromptRequestPayload payload, ProtocolContext context) {
        try {
            List<LLMPromptResultPayload.RagHitPayload> ragHits = new ArrayList<>();
            return llmService.submitTaskWithUsage(request, ragHits)
                    .thenAccept(result -> {
                        respondLLMPromptResult(envelope, LLMPromptResultPayload.completed(
                                payload.requestId(),
                                result == null ? "" : result.text(),
                                result == null ? "" : result.thinkingContent(),
                                ragHits,
                                LLMService.toUsagePayload(result == null ? null : result.usage())
                        ));
                        complete(context, envelope);
                    })
                    .exceptionally(ex -> {
                        Throwable cause = unwrapCompletion(ex);
                        if (isTaskCancellation(cause)) {
                            respondLLMPromptResult(envelope, LLMPromptResultPayload.cancelled(payload.requestId(), "", ragHits));
                            cancel(context, envelope, cancellationReasonCode(cause), failureMessage(cause));
                            return null;
                        }
                        respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                                payload.requestId(),
                                "LLM_INFERENCE_FAILED",
                                failureMessage(cause),
                                "",
                                ragHits
                        ));
                        fail(context, envelope, "LLM_INFERENCE_FAILED", failureMessage(cause), cause);
                        return null;
                    });
        } catch (Exception e) {
            respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                    payload.requestId(),
                    "LLM_INFERENCE_FAILED",
                    e.getMessage()
            ));
            fail(context, envelope, "LLM_INFERENCE_FAILED", e.getMessage(), e);
            return CompletableFuture.completedFuture(null);
        }
    }

    private CompletableFuture<Void> startTaskStreamRequest(TianshuEnvelope envelope, LLMRequest request, LLMPromptRequestPayload payload, ProtocolContext context) {
        StringBuilder visibleCollected = new StringBuilder();
        StringBuilder thinkingCollected = new StringBuilder();
        int[] index = {0};
        boolean[] ragHitsPublished = {false};
        List<LLMPromptResultPayload.RagHitPayload> ragHits = new ArrayList<>();

        java.util.concurrent.atomic.AtomicReference<LLMService.LLMStreamFinish> finishRef = new java.util.concurrent.atomic.AtomicReference<>();
        CompletableFuture<com.rheinmetal.tianshu.libs.llm.LlmGenerationResult> future = llmService.submitTaskStreamWithUsage(request, token -> {
            publishInitialRagHits(envelope, payload, ragHits, index, ragHitsPublished);
            if (token != null && !token.isEmpty()) {
                visibleCollected.append(token);
                LLMPromptStreamChunkPayload chunk = LLMPromptStreamChunkPayload.chunk(
                        payload.requestId(),
                        token,
                        index[0]++
                );
                publishLLMPromptStreamChunk(envelope, chunk);
            }
        }, thinking -> {
            if (thinking != null && !thinking.isEmpty()) {
                thinkingCollected.append(thinking);
                publishLLMPromptStreamChunk(envelope, LLMPromptStreamChunkPayload.thinking(
                        payload.requestId(),
                        thinking,
                        index[0]++
                ));
            }
        }, finishRef::set, ragHits);
        publishInitialRagHits(envelope, payload, ragHits, index, ragHitsPublished);

        return future.thenAccept(result -> {
            LLMPromptResultPayload.TokenUsagePayload usage = finishRef.get() != null
                    ? finishRef.get().usage()
                    : LLMService.toUsagePayload(result == null ? null : result.usage());
            String resultText = result == null ? "" : result.text();
            String responseText = visibleCollected.length() > 0 ? visibleCollected.toString() : resultText;
            LLMService.LLMStreamFinish finish = finishRef.get() != null
                    ? finishRef.get()
                    : new LLMService.LLMStreamFinish("COMPLETED", usage, null);
            String thinkingContent = streamThinkingContent(thinkingCollected, finish.thinkingContent(), result == null ? "" : result.thinkingContent());
            publishLLMPromptStreamEnd(envelope, index[0], finish.type(), finish.usage(), failureMessage(finish.error()), thinkingContent);
            respondLLMPromptResult(envelope, LLMPromptResultPayload.completed(
                    payload.requestId(),
                    responseText,
                    thinkingContent,
                    ragHits,
                    usage
            ));
            complete(context, envelope);
        }).exceptionally(ex -> {
            Throwable cause = unwrapCompletion(ex);
            LLMPromptResultPayload.TokenUsagePayload usage = finishRef.get() == null
                    ? LLMPromptResultPayload.TokenUsagePayload.empty()
                    : finishRef.get().usage();
            if (isTaskCancellation(cause)) {
                publishLLMPromptStreamEnd(envelope, index[0], "CANCELLED", usage, null, thinkingCollected.toString());
                respondLLMPromptResult(envelope, LLMPromptResultPayload.cancelled(
                        payload.requestId(),
                        visibleCollected.toString(),
                        thinkingCollected.toString(),
                        ragHits,
                        usage
                ));
                cancel(context, envelope, cancellationReasonCode(cause), failureMessage(cause));
                return null;
            }
            publishLLMPromptStreamEnd(envelope, index[0], "FAILED", usage, failureMessage(cause), thinkingCollected.toString());
            respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                    payload.requestId(),
                    "LLM_INFERENCE_FAILED",
                    failureMessage(cause),
                    visibleCollected.toString(),
                    thinkingCollected.toString(),
                    ragHits,
                    usage
            ));
            fail(context, envelope, "LLM_INFERENCE_FAILED", failureMessage(cause), cause);
            return null;
        });
    }

    private void publishInitialRagHits(TianshuEnvelope envelope, LLMPromptRequestPayload payload,
                                       List<LLMPromptResultPayload.RagHitPayload> ragHits, int[] index,
                                       boolean[] published) {
        if (published[0] || ragHits == null || ragHits.isEmpty()) {
            return;
        }
        published[0] = true;
        publishLLMPromptStreamChunk(envelope, LLMPromptStreamChunkPayload.chunk(
                payload.requestId(),
                "",
                index[0]++,
                List.copyOf(ragHits)
        ));
    }

    private String streamThinkingContent(StringBuilder collected, String... fallbacks) {
        if (collected != null && collected.length() > 0) {
            return collected.toString();
        }
        if (fallbacks == null) {
            return "";
        }
        for (String fallback : fallbacks) {
            if (fallback != null && !fallback.isEmpty()) {
                return fallback;
            }
        }
        return "";
    }

    private static String streamRequestId(TianshuEnvelope parent) {
        if (parent != null && parent.payload() instanceof LLMPromptRequestPayload payload) {
            return payload.requestId();
        }
        return parent != null && parent.header() != null ? parent.header().traceId() : "";
    }

    private LLMRequest toLLMRequest(LLMPromptRequestPayload payload) {
        LLMRequest request = new LLMRequest();

        request.setMaxTokens(payload.maxTokens());
        request.setTemperature(payload.temperature());
        request.setTopK(payload.topK());
        request.setTopP(payload.topP());
        request.setMinP(payload.minP());
        request.setPenaltyRepeat(payload.penaltyRepeat());
        request.setPenaltyFreq(payload.penaltyFreq());
        request.setPenaltyPresent(payload.penaltyPresent());
        request.setPenaltyLastN(payload.penaltyLastN());
        request.setStream(payload.stream());
        request.setThinking(payload.thinking());
        request.setCaptureThinkingContent(payload.captureThinkingContent());
        request.setToolsJson(payload.toolsJson());
        request.setLane(payload.lane());
        request.setTaskPriority(payload.taskPriority());
        request.setTaskPreemptible(payload.taskPreemptible());
        request.setInferencePolicy(toInferencePolicy(payload.inferencePolicy()));

        if (payload.chunks() != null) {
            for (LLMPromptRequestPayload.ChunkPayload chunk : payload.chunks()) {
                if ("message".equalsIgnoreCase(chunk.type())) {
                    request.addChunk(toMessageChunk(chunk));
                } else if ("rag".equalsIgnoreCase(chunk.type())) {
                    request.addChunk(toRagChunk(chunk));
                }
            }
        }

        return request;
    }

    private LlmInferencePolicy toInferencePolicy(LLMPromptRequestPayload.InferencePolicyPayload payload) {
        if (payload == null) {
            return LlmInferencePolicy.defaults();
        }
        return new LlmInferencePolicy(payload.frameGuardEnabled(), payload.targetFps(), payload.mtpEnabled());
    }

    private Chunk toMessageChunk(LLMPromptRequestPayload.ChunkPayload chunk) {
        if (chunk.messageContent() == null || chunk.messageContent().isEmpty()) {
            return Chunk.message(List.of());
        }

        List<MessageItem> items = chunk.messageContent().stream()
                .map(m -> new MessageItem(m.role(), m.content()))
                .toList();

        return Chunk.message(items);
    }

    private Chunk toRagChunk(LLMPromptRequestPayload.ChunkPayload chunk) {
        return Chunk.rag(
                chunk.uid(),
                chunk.prompt(),
                chunk.ragContent(),
                Boolean.TRUE.equals(chunk.useCache()),
                Boolean.TRUE.equals(chunk.includeRagHits()),
                chunk.memoryRagTokenBudget() != null ? chunk.memoryRagTokenBudget() : 1000
        );
    }

    public void handleLLMCacheManage(TianshuEnvelope envelope) {
        handleLLMCacheManage(envelope, null);
    }

    public void handleLLMCacheManage(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope == null || !(envelope.payload() instanceof LLMCacheManagePayload payload)) {
            complete(context, envelope);
            return;
        }

        if (llmService == null) {
            respondTo(envelope, PayloadType.LLM_CACHE_MANAGE_RESULT,
                    LLMCacheManageResultPayload.failed(payload.uid(), "LLM service is not initialized"));
            fail(context, envelope, "LLM_SERVICE_NOT_READY", "LLM service is not initialized", null);
            return;
        }

        try {
            LLMCacheManageResultPayload result = switch (payload.action()) {
                case LLMCacheManagePayload.ACTION_UPSERT_ENTRY -> {
                    llmService.upsertRagEntry(payload.uid(), payload.entryId(), payload.content(), payload.vector());
                    yield LLMCacheManageResultPayload.upserted(payload.uid(), payload.entryId());
                }
                case LLMCacheManagePayload.ACTION_PATCH_ENTRY -> {
                    llmService.patchRagEntry(payload.uid(), payload.entryId(), payload.content(), payload.vector(),
                            Boolean.TRUE.equals(payload.updateContent()), Boolean.TRUE.equals(payload.updateVector()));
                    yield LLMCacheManageResultPayload.patched(payload.uid(), payload.entryId(), llmService.hasRagEntry(payload.uid(), payload.entryId()));
                }
                case LLMCacheManagePayload.ACTION_DELETE_ENTRY -> {
                    llmService.deleteRagEntry(payload.uid(), payload.entryId());
                    yield LLMCacheManageResultPayload.deleted(payload.uid(), payload.entryId());
                }
                case LLMCacheManagePayload.ACTION_CLEAR_UID -> {
                    llmService.clearRagUid(payload.uid());
                    yield LLMCacheManageResultPayload.cleared(payload.uid());
                }
                case LLMCacheManagePayload.ACTION_REGISTER_LIBRARY -> {
                    var library = llmService.registerRagLibrary(payload.uid(), payload.modid(), payload.visibility(), payload.tags());
                    yield LLMCacheManageResultPayload.registered(toLibraryPayload(library));
                }
                case LLMCacheManagePayload.ACTION_UNREGISTER_LIBRARY -> {
                    llmService.unregisterRagLibrary(payload.uid());
                    yield LLMCacheManageResultPayload.unregistered(payload.uid());
                }
                case LLMCacheManagePayload.ACTION_QUERY_UID -> {
                    boolean exists = llmService.hasRagUid(payload.uid());
                    yield LLMCacheManageResultPayload.queried(payload.uid(), exists, toLibraryPayload(llmService.ragLibrary(payload.uid())));
                }
                case LLMCacheManagePayload.ACTION_SEARCH_UID -> {
                    List<LLMService.RagLibrarySearchResult> results = llmService.searchRagLibraryByUid(
                            payload.uid(),
                            payload.queryText(),
                            payload.topK(),
                            payload.threshold()
                    );
                    yield LLMCacheManageResultPayload.searched(payload.action(), payload.uid(), toHitGroups(results), toLibraryPayloads(results));
                }
                case LLMCacheManagePayload.ACTION_SEARCH_MODID -> {
                    List<LLMService.RagLibrarySearchResult> results = llmService.searchSharedRagLibrariesByModid(
                            payload.modid(),
                            payload.queryText(),
                            payload.topK(),
                            payload.threshold()
                    );
                    yield LLMCacheManageResultPayload.searched(payload.action(), "", toHitGroups(results), toLibraryPayloads(results));
                }
                case LLMCacheManagePayload.ACTION_SEARCH_TAGS -> {
                    List<LLMService.RagLibrarySearchResult> results = llmService.searchSharedRagLibrariesByTags(
                            payload.tags(),
                            payload.queryText(),
                            payload.topK(),
                            payload.threshold()
                    );
                    yield LLMCacheManageResultPayload.searched(payload.action(), "", toHitGroups(results), toLibraryPayloads(results));
                }
                case LLMCacheManagePayload.ACTION_SEARCH_INLINE_CONTENTS -> {
                    List<RagCacheManager.RagEntrySearchResult> entries =
                            llmService.searchInlineRagContents(payload.contents(), payload.queryText(), payload.topK(), payload.threshold());
                    List<LLMCacheManageResultPayload.HitGroupPayload> hits = entries.isEmpty()
                            ? List.of()
                            : List.of(toHitGroup(payload.uid(), entries));
                    yield LLMCacheManageResultPayload.searched(payload.action(), payload.uid(), hits, List.of());
                }
                default -> LLMCacheManageResultPayload.failed(payload.uid(), "Unknown action: " + payload.action());
            };

            respondTo(envelope, PayloadType.LLM_CACHE_MANAGE_RESULT, result);
            complete(context, envelope);
        } catch (Exception e) {
            respondTo(envelope, PayloadType.LLM_CACHE_MANAGE_RESULT,
                    LLMCacheManageResultPayload.failed(payload.uid(), e.getMessage()));
            fail(context, envelope, "LLM_CACHE_MANAGE_FAILED", e.getMessage(), e);
        }
    }

    private List<LLMCacheManageResultPayload.HitGroupPayload> toHitGroups(List<LLMService.RagLibrarySearchResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return results.stream()
                .map(result -> LLMCacheManageResultPayload.HitGroupPayload.of(
                        result.uid(),
                        result.entries().stream()
                                .map(hit -> LLMCacheManageResultPayload.HitEntryPayload.of(hit.entryId(), hit.content(), hit.score()))
                                .toList()
                ))
                .toList();
    }

    private LLMCacheManageResultPayload.HitGroupPayload toHitGroup(
            String uid,
            List<RagCacheManager.RagEntrySearchResult> entries
    ) {
        return LLMCacheManageResultPayload.HitGroupPayload.of(
                uid,
                entries == null ? List.of() : entries.stream()
                        .map(hit -> LLMCacheManageResultPayload.HitEntryPayload.of(hit.entryId(), hit.content(), hit.score()))
                        .toList()
        );
    }

    private List<LLMCacheManageResultPayload.LibraryPayload> toLibraryPayloads(List<LLMService.RagLibrarySearchResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return results.stream()
                .map(LLMService.RagLibrarySearchResult::library)
                .filter(java.util.Objects::nonNull)
                .map(this::toLibraryPayload)
                .toList();
    }

    private LLMCacheManageResultPayload.LibraryPayload toLibraryPayload(com.rheinmetal.tianshu.function.llm.service.RagLibraryRegistry.RagLibraryMeta meta) {
        if (meta == null) {
            return null;
        }
        return LLMCacheManageResultPayload.LibraryPayload.of(meta.uid(), meta.modid(), meta.visibility(), meta.tags());
    }

    public void handleLLMPrimitiveQuery(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope == null || !(envelope.payload() instanceof LLMPrimitiveQueryPayload payload)) {
            complete(context, envelope);
            return;
        }

        try {
            LLMPrimitiveResultPayload result = switch (payload.queryType()) {
                case LLMPrimitiveQueryPayload.QUERY_TYPE_TOKEN_COUNT ->
                        llmService != null
                                ? llmService.tokenCountResponse(payload.requestId(), mergeTokenCountInput(payload))
                                : LLMPrimitiveResultPayload.failed(payload.requestId(), payload.queryType(), "LLM_SERVICE_NOT_READY", "LLM service is not initialized");
                case LLMPrimitiveQueryPayload.QUERY_TYPE_EMBED ->
                        llmService != null
                                ? llmService.embedResponse(
                                        payload.requestId(),
                                        payload.texts(),
                                        Boolean.TRUE.equals(payload.includeVector()),
                                        Boolean.TRUE.equals(payload.includeEmbeddingDetails())
                                )
                                : LLMPrimitiveResultPayload.failed(payload.requestId(), payload.queryType(), "LLM_SERVICE_NOT_READY", "LLM service is not initialized");
                case LLMPrimitiveQueryPayload.QUERY_TYPE_STATUS ->
                        llmService != null
                                ? llmService.runtimeSnapshotResponse(payload.requestId(), Boolean.TRUE.equals(payload.includeRuntimeDetails()))
                                : LLMPrimitiveResultPayload.runtime(payload.requestId(), LLMRuntimeSnapshotPayload.unavailable());
                default ->
                        LLMPrimitiveResultPayload.failed(payload.requestId(), payload.queryType(), "UNKNOWN_QUERY_TYPE", "Unknown primitive query type");
            };
            respondTo(envelope, PayloadType.LLM_PRIMITIVE_RESULT, result);
            complete(context, envelope);
        } catch (Exception e) {
            respondTo(envelope, PayloadType.LLM_PRIMITIVE_RESULT,
                    LLMPrimitiveResultPayload.failed(payload.requestId(), payload.queryType(), "LLM_PRIMITIVE_QUERY_FAILED", e.getMessage()));
            fail(context, envelope, "LLM_PRIMITIVE_QUERY_FAILED", e.getMessage(), e);
        }
    }

    private LLMRequest mergeTokenCountInput(LLMPrimitiveQueryPayload payload) {
        LLMRequest request = new LLMRequest();
        if (payload == null) {
            return request;
        }
        if (payload.text() != null && !payload.text().isBlank()) {
            request.addChunk(Chunk.message(List.of(MessageItem.user(payload.text()))));
        }
        if (payload.messages() != null && !payload.messages().isEmpty()) {
            List<MessageItem> messages = payload.messages().stream()
                    .map(m -> new MessageItem(m.role(), m.content()))
                    .toList();
            request.addChunk(Chunk.message(messages));
        }
        if (payload.chunks() != null && !payload.chunks().isEmpty()) {
            for (LLMPrimitiveQueryPayload.ChunkPayload chunk : payload.chunks()) {
                if (chunk == null) {
                    continue;
                }
                if ("message".equalsIgnoreCase(chunk.type())) {
                    List<MessageItem> messages = chunk.messageContent().stream()
                            .map(m -> new MessageItem(m.role(), m.content()))
                            .toList();
                    request.addChunk(Chunk.message(messages));
                } else if ("rag".equalsIgnoreCase(chunk.type())) {
                    request.addChunk(Chunk.rag(
                            chunk.uid(),
                            chunk.prompt(),
                            chunk.ragContent(),
                            Boolean.TRUE.equals(chunk.useCache()),
                            Boolean.TRUE.equals(chunk.includeRagHits()),
                            chunk.memoryRagTokenBudget() != null ? chunk.memoryRagTokenBudget() : 1000
                    ));
                }
            }
        }
        return request;
    }

    private void complete(ProtocolContext context, TianshuEnvelope envelope) {
        if (context != null && envelope != null) {
            context.complete(envelope.envelopeId());
        }
    }

    private void fail(ProtocolContext context, TianshuEnvelope envelope, String code, String message, Throwable throwable) {
        if (context != null && envelope != null) {
            context.fail(envelope.envelopeId(), code, message, throwable);
        }
    }

    private void cancel(ProtocolContext context, TianshuEnvelope envelope, String code, String message) {
        if (context != null && envelope != null) {
            context.cancel(envelope.envelopeId(), code, message);
        }
    }

    private static Throwable unwrapCompletion(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isTaskCancellation(Throwable throwable) {
        Throwable current = unwrapCompletion(throwable);
        if (current instanceof CancellationException || current instanceof InterruptedException) {
            return true;
        }
        String message = current == null ? "" : String.valueOf(current.getMessage()).toLowerCase();
        return message.contains("cancel")
                || message.contains("preempt")
                || message.contains("interrupt");
    }

    private static String cancellationReasonCode(Throwable throwable) {
        Throwable current = unwrapCompletion(throwable);
        String message = current == null ? "" : String.valueOf(current.getMessage()).toLowerCase();
        if (message.contains("preempt")) {
            return "LLM_TASK_PREEMPTED";
        }
        if (current instanceof InterruptedException || message.contains("interrupt")) {
            return "LLM_TASK_INTERRUPTED";
        }
        return "LLM_TASK_CANCELLED";
    }

    private static String failureMessage(Throwable throwable) {
        Throwable current = unwrapCompletion(throwable);
        String message = current == null ? null : current.getMessage();
        return message == null || message.isBlank() ? "LLM request failed" : message;
    }
}


