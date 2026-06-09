package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.function.llm.service.Chunk;
import com.rheinmetal.tianshu.function.llm.service.LLMRequest;
import com.rheinmetal.tianshu.function.llm.service.LLMService;
import com.rheinmetal.tianshu.function.llm.service.MessageItem;
import com.rheinmetal.tianshu.function.ia.payload.DialogueLlmUsageAuthorizationRequestPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueLlmUsageAuthorizationResultPayload;
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
import com.rheinmetal.tianshu.protocol.payload.LlmStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

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
        LLMPromptStreamChunkPayload endPayload = LLMPromptStreamChunkPayload.end(
                parent.header() != null ? parent.header().traceId() : "",
                index
        );
        return respondTo(parent, PayloadType.LLM_PROMPT_STREAM_CHUNK, endPayload);
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
        publishStatus(envelope, payload, LlmStatusPayload.ACCEPTED);

        if (llmService == null) {
            publishStatus(envelope, payload, LlmStatusPayload.FAILED);
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
                publishStatus(envelope, payload, LlmStatusPayload.STREAMING);
                if (handleStreamRequest(envelope, request, payload)) {
                    publishStatus(envelope, payload, LlmStatusPayload.COMPLETED);
                    complete(context, envelope);
                } else {
                    publishStatus(envelope, payload, LlmStatusPayload.FAILED);
                    fail(context, envelope, "LLM_INFERENCE_FAILED", "LLM stream chat failed", null);
                }
            } else {
                if (handleChatRequest(envelope, request, payload)) {
                    publishStatus(envelope, payload, LlmStatusPayload.COMPLETED);
                    complete(context, envelope);
                } else {
                    publishStatus(envelope, payload, LlmStatusPayload.FAILED);
                    fail(context, envelope, "LLM_INFERENCE_FAILED", "LLM chat failed", null);
                }
            }
        } catch (Exception e) {
            publishStatus(envelope, payload, LlmStatusPayload.FAILED);
            respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                    payload.requestId(),
                    "LLM_REQUEST_FAILED",
                    e.getMessage()
            ));
            fail(context, envelope, "LLM_REQUEST_FAILED", e.getMessage(), e);
        }
    }

    private void admitTaskRequest(TianshuEnvelope envelope, LLMRequest request, LLMPromptRequestPayload payload, ProtocolContext context, boolean stream) {
        LlmTaskAdmissionController.AdmissionResult result = taskAdmissionController.submit(
                payload.taskPriority(),
                payload.taskPreemptible(),
                () -> {
                    if (stream) {
                        publishStatus(envelope, payload, LlmStatusPayload.STREAMING);
                        return startTaskStreamRequest(envelope, request, payload, context);
                    }
                    return startTaskRequest(envelope, request, payload, context);
                },
                (code, message) -> rejectTask(envelope, payload, context, code, message)
        );
        if (result.queued()) {
            publishStatus(envelope, payload, LlmStatusPayload.QUEUED);
        }
    }

    private void rejectTask(TianshuEnvelope envelope, LLMPromptRequestPayload payload, ProtocolContext context, String code, String message) {
        respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                payload.requestId(),
                code,
                message
        ));
        publishStatus(envelope, payload, LlmStatusPayload.FAILED);
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
        publishStatus(envelope, payload, LlmStatusPayload.FAILED);
        respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(payload.requestId(), code, message));
        fail(context, envelope, code, message, null);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean handleChatRequest(TianshuEnvelope envelope, LLMRequest request, LLMPromptRequestPayload payload) {
        try {
            LLMService.LLMResult result = llmService.chat(request);
            respondLLMPromptResult(envelope, LLMPromptResultPayload.completed(
                    payload.requestId(),
                    responseText(payload, result.text()),
                    result.ragHits()
            ));
            return true;
        } catch (Exception e) {
            respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                    payload.requestId(),
                    "LLM_INFERENCE_FAILED",
                    e.getMessage()
            ));
            return false;
        }
    }

    private boolean handleStreamRequest(TianshuEnvelope envelope, LLMRequest request, LLMPromptRequestPayload payload) {
        StringBuilder visibleCollected = new StringBuilder();
        LlmThinkingContentFilter thinkingFilter = responseStreamFilter(payload);
        int[] index = {0};
        List<LLMPromptResultPayload.RagHitPayload> ragHits = new ArrayList<>();

        try {
            llmService.chatStream(request, token -> {
                if (token != null && !token.isEmpty()) {
                    String visibleToken = responseStreamToken(thinkingFilter, token);
                    if (visibleToken.isEmpty()) {
                        return;
                    }
                    visibleCollected.append(visibleToken);
                    LLMPromptStreamChunkPayload chunk = LLMPromptStreamChunkPayload.chunk(
                            payload.requestId(),
                            visibleToken,
                            index[0]++
                    );
                    publishLLMPromptStreamChunk(envelope, chunk);
                }
            }, ragHits);

            String finalVisibleToken = responseStreamFlush(thinkingFilter);
            if (!finalVisibleToken.isEmpty()) {
                visibleCollected.append(finalVisibleToken);
                publishLLMPromptStreamChunk(envelope, LLMPromptStreamChunkPayload.chunk(
                        payload.requestId(),
                        finalVisibleToken,
                        index[0]++
                ));
            }
            publishLLMPromptStreamEnd(envelope, index[0]);
            respondLLMPromptResult(envelope, LLMPromptResultPayload.completed(
                    payload.requestId(),
                    visibleCollected.toString(),
                    ragHits
            ));
            return true;
        } catch (Exception e) {
            respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                    payload.requestId(),
                    "LLM_INFERENCE_FAILED",
                    e.getMessage(),
                    visibleCollected.toString()
            ));
            return false;
        }
    }

    private CompletableFuture<Void> startTaskRequest(TianshuEnvelope envelope, LLMRequest request, LLMPromptRequestPayload payload, ProtocolContext context) {
        try {
            List<LLMPromptResultPayload.RagHitPayload> ragHits = new ArrayList<>();
            return llmService.submitTask(request, ragHits)
                    .thenAccept(text -> {
                        respondLLMPromptResult(envelope, LLMPromptResultPayload.completed(payload.requestId(), responseText(payload, text), ragHits));
                        publishStatus(envelope, payload, LlmStatusPayload.COMPLETED);
                        complete(context, envelope);
                    })
                    .exceptionally(ex -> {
                        Throwable cause = unwrapCompletion(ex);
                        if (isTaskCancellation(cause)) {
                            respondLLMPromptResult(envelope, LLMPromptResultPayload.cancelled(payload.requestId(), ""));
                            publishStatus(envelope, payload, LlmStatusPayload.CANCELLED);
                            cancel(context, envelope, cancellationReasonCode(cause), failureMessage(cause));
                            return null;
                        }
                        respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                                payload.requestId(),
                                "LLM_INFERENCE_FAILED",
                                failureMessage(cause)
                        ));
                        publishStatus(envelope, payload, LlmStatusPayload.FAILED);
                        fail(context, envelope, "LLM_INFERENCE_FAILED", failureMessage(cause), cause);
                        return null;
                    });
        } catch (Exception e) {
            respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                    payload.requestId(),
                    "LLM_INFERENCE_FAILED",
                    e.getMessage()
            ));
            publishStatus(envelope, payload, LlmStatusPayload.FAILED);
            fail(context, envelope, "LLM_INFERENCE_FAILED", e.getMessage(), e);
            return CompletableFuture.completedFuture(null);
        }
    }

    private CompletableFuture<Void> startTaskStreamRequest(TianshuEnvelope envelope, LLMRequest request, LLMPromptRequestPayload payload, ProtocolContext context) {
        StringBuilder visibleCollected = new StringBuilder();
        LlmThinkingContentFilter thinkingFilter = responseStreamFilter(payload);
        int[] index = {0};
        List<LLMPromptResultPayload.RagHitPayload> ragHits = new ArrayList<>();

        CompletableFuture<String> future = llmService.submitTaskStream(request, token -> {
            if (token != null && !token.isEmpty()) {
                String visibleToken = responseStreamToken(thinkingFilter, token);
                if (visibleToken.isEmpty()) {
                    return;
                }
                visibleCollected.append(visibleToken);
                LLMPromptStreamChunkPayload chunk = LLMPromptStreamChunkPayload.chunk(
                        payload.requestId(),
                        visibleToken,
                        index[0]++
                );
                publishLLMPromptStreamChunk(envelope, chunk);
            }
        }, ragHits);

        return future.thenAccept(text -> {
            String finalVisibleToken = responseStreamFlush(thinkingFilter);
            if (!finalVisibleToken.isEmpty()) {
                visibleCollected.append(finalVisibleToken);
                publishLLMPromptStreamChunk(envelope, LLMPromptStreamChunkPayload.chunk(
                        payload.requestId(),
                        finalVisibleToken,
                        index[0]++
                ));
            }
            String responseText = visibleCollected.length() > 0 ? visibleCollected.toString() : responseText(payload, text);
            publishLLMPromptStreamEnd(envelope, index[0]);
            respondLLMPromptResult(envelope, LLMPromptResultPayload.completed(
                    payload.requestId(),
                    responseText,
                    ragHits
            ));
            publishStatus(envelope, payload, LlmStatusPayload.COMPLETED);
            complete(context, envelope);
        }).exceptionally(ex -> {
            Throwable cause = unwrapCompletion(ex);
            if (isTaskCancellation(cause)) {
                publishLLMPromptStreamEnd(envelope, index[0]);
                respondLLMPromptResult(envelope, LLMPromptResultPayload.cancelled(
                        payload.requestId(),
                        visibleCollected.toString()
                ));
                publishStatus(envelope, payload, LlmStatusPayload.CANCELLED);
                cancel(context, envelope, cancellationReasonCode(cause), failureMessage(cause));
                return null;
            }
            publishLLMPromptStreamEnd(envelope, index[0]);
            respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                    payload.requestId(),
                    "LLM_INFERENCE_FAILED",
                    failureMessage(cause),
                    visibleCollected.toString()
            ));
            publishStatus(envelope, payload, LlmStatusPayload.FAILED);
            fail(context, envelope, "LLM_INFERENCE_FAILED", failureMessage(cause), cause);
            return null;
        });
    }

    private String responseText(LLMPromptRequestPayload payload, String text) {
        if (includeThinkingContent(payload)) {
            return text == null ? "" : text;
        }
        return LlmThinkingContentFilter.strip(text);
    }

    private LlmThinkingContentFilter responseStreamFilter(LLMPromptRequestPayload payload) {
        return includeThinkingContent(payload) ? null : new LlmThinkingContentFilter();
    }

    private String responseStreamToken(LlmThinkingContentFilter filter, String token) {
        return filter == null ? (token == null ? "" : token) : filter.append(token);
    }

    private String responseStreamFlush(LlmThinkingContentFilter filter) {
        return filter == null ? "" : filter.flush();
    }

    private boolean includeThinkingContent(LLMPromptRequestPayload payload) {
        return payload != null && Boolean.TRUE.equals(payload.includeThinkingContent());
    }

    private LLMRequest toLLMRequest(LLMPromptRequestPayload payload) {
        LLMRequest request = new LLMRequest();

        request.setMaxTokens(payload.maxTokens());
        request.setTemperature(payload.temperature());
        request.setStream(payload.stream());
        request.setThinking(payload.thinking());
        request.setLane(payload.lane());
        request.setTaskPriority(payload.taskPriority());
        request.setTaskPreemptible(payload.taskPreemptible());

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
                case LLMCacheManagePayload.ACTION_EVICT_ALL -> {
                    llmService.evictCache(payload.uid());
                    yield LLMCacheManageResultPayload.evicted(payload.uid(), true);
                }
                case LLMCacheManagePayload.ACTION_EVICT_CONTENT -> {
                    for (String content : payload.contents()) {
                        llmService.evictCache(payload.uid(), content);
                    }
                    yield LLMCacheManageResultPayload.evicted(payload.uid(), true);
                }
                case LLMCacheManagePayload.ACTION_QUERY -> {
                    boolean exists = llmService.hasCache(payload.uid());
                    yield LLMCacheManageResultPayload.queried(payload.uid(), exists);
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

    private void publishStatus(TianshuEnvelope envelope, LLMPromptRequestPayload payload, String status) {
        if (envelope == null || payload == null) {
            return;
        }
        publishTopic(envelope, ProtocolTopics.LLM_STATUS, PayloadType.LLM_STATUS, new LlmStatusPayload(
                payload.requestId(),
                envelope.traceId(),
                payload.lane(),
                status,
                System.currentTimeMillis()
        ));
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
