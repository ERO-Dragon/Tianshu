package com.rheinmetal.tianshu.function.weaving;

import com.rheinmetal.tianshu.function.weaving.service.LLMRequest;
import com.rheinmetal.tianshu.function.weaving.service.LLMService;
import com.rheinmetal.tianshu.function.weaving.usage.LlmPromptCacheUsage;
import com.rheinmetal.tianshu.function.weaving.usage.LlmUsageLedger;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueLlmUsageAuthorizationRequestPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueLlmUsageAuthorizationResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class LlmPromptRequestHandler {
    private final LlmProtocolAdapter adapter;
    private final LlmTaskAdmissionController taskAdmissionController;
    private final LlmPromptPayloadMapper payloadMapper;
    private final LlmUsageLedger usageLedger;

    LlmPromptRequestHandler(
            LlmProtocolAdapter adapter,
            LlmTaskAdmissionController taskAdmissionController,
            LlmPromptPayloadMapper payloadMapper,
            LlmUsageLedger usageLedger
    ) {
        this.adapter = adapter;
        this.taskAdmissionController = taskAdmissionController;
        this.payloadMapper = payloadMapper;
        this.usageLedger = usageLedger;
    }

    void handle(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope == null || !(envelope.payload() instanceof LLMPromptRequestPayload payload)) {
            complete(context, envelope);
            return;
        }
        // One request, one accounting record: the same instance follows the request through every
        // lane and the dialogue authorization hop, so the ledger sees each finished request once.
        UsageRecording usage = new UsageRecording(envelope);
        if (adapter.currentLlmService() == null) {
            reject(envelope, payload, context, "LLM_SERVICE_NOT_READY", "LLM service is not initialized");
            return;
        }
        if (requiresDialogueAuthorization(payload)) {
            authorizeDialogueChatThenHandle(envelope, payload, context, usage);
        } else {
            handleAuthorized(envelope, payload, context, usage);
        }
    }

    private void handleAuthorized(
            TianshuEnvelope envelope,
            LLMPromptRequestPayload payload,
            ProtocolContext context,
            UsageRecording usage
    ) {
        LLMService service = adapter.currentLlmService();
        if (service == null) {
            reject(envelope, payload, context, "LLM_SERVICE_NOT_READY", "LLM service is not initialized");
            return;
        }
        try {
            LLMRequest request = payloadMapper.toRequest(payload);
            boolean stream = Boolean.TRUE.equals(payload.stream());
            boolean task = "TASK".equalsIgnoreCase(payload.lane());
            if (task) {
                admitTaskRequest(service, envelope, request, payload, context, stream, usage);
            } else if (stream) {
                if (handleChatStream(service, envelope, request, payload, usage)) {
                    complete(context, envelope);
                } else {
                    fail(context, envelope, "LLM_INFERENCE_FAILED", "LLM stream chat failed", null);
                }
            } else {
                handleChat(service, envelope, request, payload, context, usage);
            }
        } catch (Exception exception) {
            reject(envelope, payload, context, "LLM_REQUEST_FAILED", exception.getMessage(), exception);
        }
    }

    private void admitTaskRequest(
            LLMService service,
            TianshuEnvelope envelope,
            LLMRequest request,
            LLMPromptRequestPayload payload,
            ProtocolContext context,
            boolean stream,
            UsageRecording usage
    ) {
        taskAdmissionController.submit(
                payload.taskPriority(),
                payload.taskPreemptible(),
                () -> stream
                        ? startTaskStream(service, envelope, request, payload, context, usage)
                        : startTask(service, envelope, request, payload, context, usage),
                (code, message) -> reject(envelope, payload, context, code, message)
        );
    }

    private void handleChat(
            LLMService service,
            TianshuEnvelope envelope,
            LLMRequest request,
            LLMPromptRequestPayload payload,
            ProtocolContext context,
            UsageRecording usage
    ) {
        try {
            LLMService.LLMResult result = service.chat(request, usage.cacheSink());
            usage.record(result.usage());
            adapter.respondLLMPromptResult(envelope, LLMPromptResultPayload.completed(
                    payload.requestId(),
                    result.text(),
                    result.thinkingContent(),
                    result.ragHits(),
                    result.usage()
            ));
            complete(context, envelope);
        } catch (Exception exception) {
            Throwable cause = unwrapCompletion(exception);
            if (isTaskCancellation(cause)) {
                adapter.respondLLMPromptResult(envelope, LLMPromptResultPayload.cancelled(
                        payload.requestId(),
                        "",
                        List.of(),
                        LLMPromptResultPayload.TokenUsagePayload.empty()
                ));
                cancel(context, envelope, cancellationReasonCode(cause), failureMessage(cause));
                return;
            }
            adapter.respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                    payload.requestId(),
                    "LLM_INFERENCE_FAILED",
                    failureMessage(cause)
            ));
            fail(context, envelope, "LLM_INFERENCE_FAILED", failureMessage(cause), cause);
        }
    }

    private boolean handleChatStream(
            LLMService service,
            TianshuEnvelope envelope,
            LLMRequest request,
            LLMPromptRequestPayload payload,
            UsageRecording usage
    ) {
        List<LLMPromptResultPayload.RagHitPayload> ragHits = new ArrayList<>();
        LlmStreamState state = new LlmStreamState(payload.requestId(), ragHits,
                chunk -> adapter.publishLLMPromptStreamChunk(envelope, chunk));
        try {
            LLMService.LLMStreamResult result = service.chatStream(
                    request,
                    state::onVisible,
                    state::onThinking,
                    ragHits,
                    usage.cacheSink()
            );
            LLMService.LLMStreamFinish finish = result.finish();
            usage.record(finish.usage());
            LlmStreamState.TerminalSnapshot terminal = state.terminal(result.text(), finish.thinkingContent());
            adapter.publishLLMPromptStreamEnd(
                    envelope,
                    terminal.nextIndex(),
                    finish.type(),
                    finish.usage(),
                    failureMessage(finish.error()),
                    terminal.thinkingContent()
            );
            adapter.respondLLMPromptResult(envelope, terminalResult(payload.requestId(), terminal, finish));
            return true;
        } catch (Exception exception) {
            Throwable cause = unwrapCompletion(exception);
            boolean cancelled = isTaskCancellation(cause);
            LlmStreamState.TerminalSnapshot terminal = state.terminal("", "");
            adapter.publishLLMPromptStreamEnd(
                    envelope,
                    terminal.nextIndex(),
                    cancelled ? "CANCELLED" : "FAILED",
                    LLMPromptResultPayload.TokenUsagePayload.empty(),
                    cancelled ? null : failureMessage(cause),
                    terminal.thinkingContent()
            );
            if (cancelled) {
                adapter.respondLLMPromptResult(envelope, LLMPromptResultPayload.cancelled(
                        payload.requestId(),
                        terminal.visibleText(),
                        terminal.thinkingContent(),
                        terminal.ragHits(),
                        LLMPromptResultPayload.TokenUsagePayload.empty()
                ));
                return true;
            }
            adapter.respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                    payload.requestId(),
                    "LLM_INFERENCE_FAILED",
                    failureMessage(cause),
                    terminal.visibleText(),
                    terminal.thinkingContent(),
                    terminal.ragHits(),
                    LLMPromptResultPayload.TokenUsagePayload.empty()
            ));
            return false;
        }
    }

    private CompletableFuture<Void> startTask(
            LLMService service,
            TianshuEnvelope envelope,
            LLMRequest request,
            LLMPromptRequestPayload payload,
            ProtocolContext context,
            UsageRecording usage
    ) {
        try {
            List<LLMPromptResultPayload.RagHitPayload> ragHits = new ArrayList<>();
            return service.submitTaskWithUsage(request, ragHits, usage.cacheSink())
                    .thenAccept(result -> {
                        LLMPromptResultPayload.TokenUsagePayload tokenUsage =
                                LLMService.toUsagePayload(result == null ? null : result.usage());
                        usage.record(tokenUsage);
                        adapter.respondLLMPromptResult(envelope, LLMPromptResultPayload.completed(
                                payload.requestId(),
                                result == null ? "" : result.text(),
                                result == null ? "" : result.thinkingContent(),
                                ragHits,
                                tokenUsage
                        ));
                        complete(context, envelope);
                    })
                    .exceptionally(exception -> {
                        Throwable cause = unwrapCompletion(exception);
                        if (isTaskCancellation(cause)) {
                            adapter.respondLLMPromptResult(
                                    envelope,
                                    LLMPromptResultPayload.cancelled(payload.requestId(), "", ragHits)
                            );
                            cancel(context, envelope, cancellationReasonCode(cause), failureMessage(cause));
                        } else {
                            adapter.respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                                    payload.requestId(),
                                    "LLM_INFERENCE_FAILED",
                                    failureMessage(cause),
                                    "",
                                    ragHits
                            ));
                            fail(context, envelope, "LLM_INFERENCE_FAILED", failureMessage(cause), cause);
                        }
                        return null;
                    });
        } catch (Exception exception) {
            reject(envelope, payload, context, "LLM_INFERENCE_FAILED", exception.getMessage(), exception);
            return CompletableFuture.completedFuture(null);
        }
    }

    private CompletableFuture<Void> startTaskStream(
            LLMService service,
            TianshuEnvelope envelope,
            LLMRequest request,
            LLMPromptRequestPayload payload,
            ProtocolContext context,
            UsageRecording usage
    ) {
        List<LLMPromptResultPayload.RagHitPayload> ragHits = new ArrayList<>();
        LlmStreamState state = new LlmStreamState(payload.requestId(), ragHits,
                chunk -> adapter.publishLLMPromptStreamChunk(envelope, chunk));
        AtomicReference<LLMService.LLMStreamFinish> finishReference = new AtomicReference<>();
        CompletableFuture<com.rheinmetal.tianshu.libs.llm.LlmGenerationResult> future =
                service.submitTaskStreamWithUsage(
                        request,
                        state::onVisible,
                        state::onThinking,
                        finishReference::set,
                        ragHits,
                        usage.cacheSink()
                );
        state.publishRagHitsIfNeeded();

        return future.thenAccept(result -> {
            LLMService.LLMStreamFinish finish = finishReference.get();
            LLMPromptResultPayload.TokenUsagePayload usagePayload = finish == null
                    ? LLMService.toUsagePayload(result == null ? null : result.usage())
                    : finish.usage();
            if (finish == null) {
                finish = new LLMService.LLMStreamFinish("COMPLETED", usagePayload, null);
            }
            usage.record(usagePayload);
            LlmStreamState.TerminalSnapshot terminal = state.terminal(
                    result == null ? "" : result.text(),
                    finish.thinkingContent(),
                    result == null ? "" : result.thinkingContent()
            );
            adapter.publishLLMPromptStreamEnd(
                    envelope,
                    terminal.nextIndex(),
                    finish.type(),
                    finish.usage(),
                    failureMessage(finish.error()),
                    terminal.thinkingContent()
            );
            adapter.respondLLMPromptResult(envelope, terminalResult(payload.requestId(), terminal, finish));
            complete(context, envelope);
        }).exceptionally(exception -> {
            Throwable cause = unwrapCompletion(exception);
            LLMPromptResultPayload.TokenUsagePayload failedUsage = finishReference.get() == null
                    ? LLMPromptResultPayload.TokenUsagePayload.empty()
                    : finishReference.get().usage();
            // A failed or cancelled task can still have consumed prompt tokens; account for them.
            usage.record(failedUsage);
            LlmStreamState.TerminalSnapshot terminal = state.terminal("", "");
            if (isTaskCancellation(cause)) {
                adapter.publishLLMPromptStreamEnd(
                        envelope,
                        terminal.nextIndex(),
                        "CANCELLED",
                        failedUsage,
                        null,
                        terminal.thinkingContent()
                );
                adapter.respondLLMPromptResult(envelope, LLMPromptResultPayload.cancelled(
                        payload.requestId(),
                        terminal.visibleText(),
                        terminal.thinkingContent(),
                        terminal.ragHits(),
                        failedUsage
                ));
                cancel(context, envelope, cancellationReasonCode(cause), failureMessage(cause));
            } else {
                adapter.publishLLMPromptStreamEnd(
                        envelope,
                        terminal.nextIndex(),
                        "FAILED",
                        failedUsage,
                        failureMessage(cause),
                        terminal.thinkingContent()
                );
                adapter.respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                        payload.requestId(),
                        "LLM_INFERENCE_FAILED",
                        failureMessage(cause),
                        terminal.visibleText(),
                        terminal.thinkingContent(),
                        terminal.ragHits(),
                        failedUsage
                ));
                fail(context, envelope, "LLM_INFERENCE_FAILED", failureMessage(cause), cause);
            }
            return null;
        });
    }

    private boolean requiresDialogueAuthorization(LLMPromptRequestPayload payload) {
        return payload != null && "CHAT".equalsIgnoreCase(payload.lane());
    }

    private void authorizeDialogueChatThenHandle(
            TianshuEnvelope envelope,
            LLMPromptRequestPayload payload,
            ProtocolContext context,
            UsageRecording usage
    ) {
        if (!payload.hasDialogueAuthorizationContext()) {
            reject(envelope, payload, context, "DIALOGUE_AUTH_CONTEXT_MISSING",
                    "CHAT LLM request requires dialogue session authorization context");
            return;
        }
        String requesterModuleId = envelope.header() == null ? "" : clean(envelope.header().sourceId());
        if (requesterModuleId.isBlank() || !requesterModuleId.equals(payload.requesterModuleId())) {
            reject(envelope, payload, context, "DIALOGUE_AUTH_REQUESTER_MISMATCH",
                    "CHAT LLM requester module must match envelope source module");
            return;
        }
        if (!adapter.hasCapability(ProtocolCapabilities.DIALOGUE_LLM_USAGE_AUTHORIZE)) {
            reject(envelope, payload, context, "DIALOGUE_AUTH_UNAVAILABLE",
                    "Dialogue LLM authorization capability is not available");
            return;
        }

        DialogueLlmUsageAuthorizationRequestPayload authorizationPayload =
                new DialogueLlmUsageAuthorizationRequestPayload(
                        payload.dialogueSessionId(),
                        requesterModuleId,
                        payload.requesterParticipantId(),
                        payload.dialogueTurnId(),
                        System.currentTimeMillis()
                );
        TianshuEnvelope authorizationEnvelope =
                adapter.buildDialogueLlmUsageAuthorizationRequest(envelope, authorizationPayload);
        adapter.registerDialogueLlmUsageAuthorizationResponse(
                authorizationEnvelope.envelopeId(),
                (responseEnvelope, responseContext) -> {
                    try {
                        if (!(responseEnvelope.payload() instanceof DialogueLlmUsageAuthorizationResultPayload result)) {
                            reject(envelope, payload, context, "DIALOGUE_AUTH_INVALID_RESPONSE",
                                    "Dialogue authorization response payload is invalid");
                            fail(responseContext, responseEnvelope, "INVALID_PAYLOAD",
                                    "Dialogue authorization response payload is invalid", null);
                            return;
                        }
                        if (!result.allowed()) {
                            String reason = result.reasonCode().isBlank()
                                    ? "DIALOGUE_AUTH_DENIED"
                                    : result.reasonCode();
                            String message = result.message().isBlank()
                                    ? "Dialogue LLM usage is not authorized"
                                    : result.message();
                            reject(envelope, payload, context, reason, message);
                            complete(responseContext, responseEnvelope);
                            return;
                        }
                        complete(responseContext, responseEnvelope);
                        handleAuthorized(envelope, payload, context, usage);
                    } finally {
                        adapter.unregisterAuthorizationResponse(authorizationEnvelope.envelopeId());
                    }
                }
        );
        adapter.submitDialogueLlmUsageAuthorizationRequest(authorizationEnvelope);
    }

    private void reject(
            TianshuEnvelope envelope,
            LLMPromptRequestPayload payload,
            ProtocolContext context,
            String code,
            String message
    ) {
        reject(envelope, payload, context, code, message, null);
    }

    private void reject(
            TianshuEnvelope envelope,
            LLMPromptRequestPayload payload,
            ProtocolContext context,
            String code,
            String message,
            Throwable cause
    ) {
        adapter.respondLLMPromptResult(
                envelope,
                LLMPromptResultPayload.failed(payload.requestId(), code, message)
        );
        fail(context, envelope, code, message, cause);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static void complete(ProtocolContext context, TianshuEnvelope envelope) {
        if (context != null && envelope != null) {
            context.complete(envelope.envelopeId());
        }
    }

    private static void fail(
            ProtocolContext context,
            TianshuEnvelope envelope,
            String code,
            String message,
            Throwable throwable
    ) {
        if (context != null && envelope != null) {
            context.fail(envelope.envelopeId(), code, message, throwable);
        }
    }

    private static void cancel(ProtocolContext context, TianshuEnvelope envelope, String code, String message) {
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
        return message.contains("cancel") || message.contains("preempt") || message.contains("interrupt");
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

    private static LLMPromptResultPayload terminalResult(
            String requestId,
            LlmStreamState.TerminalSnapshot terminal,
            LLMService.LLMStreamFinish finish
    ) {
        String type = finish == null || finish.type() == null ? "COMPLETED" : finish.type().trim().toUpperCase();
        LLMPromptResultPayload.TokenUsagePayload usage = finish == null
                ? LLMPromptResultPayload.TokenUsagePayload.empty()
                : finish.usage();
        if ("CANCELLED".equals(type)) {
            return LLMPromptResultPayload.cancelled(
                    requestId,
                    terminal.visibleText(),
                    terminal.thinkingContent(),
                    terminal.ragHits(),
                    usage
            );
        }
        if ("FAILED".equals(type)) {
            return LLMPromptResultPayload.failed(
                    requestId,
                    "LLM_INFERENCE_FAILED",
                    failureMessage(finish.error()),
                    terminal.visibleText(),
                    terminal.thinkingContent(),
                    terminal.ragHits(),
                    usage
            );
        }
        return LLMPromptResultPayload.completed(
                requestId,
                terminal.visibleText(),
                terminal.thinkingContent(),
                terminal.ragHits(),
                usage
        );
    }

    /**
     * Accounts for exactly one finished request.
     *
     * <p>Attribution is the envelope's {@code sourceId}: every request names the module that issued
     * it, so per-module figures need no separate instrumentation layer.
     *
     * <p>The provider reports the prompt-cache split only inside the inference client, so this
     * instance is handed out as a sink before the call and read back afterwards. A request that
     * finishes through two callbacks (a cancellation racing a completion) is still recorded once.
     */
    private final class UsageRecording {
        private final String sourceId;
        private final AtomicReference<LlmPromptCacheUsage> cacheUsage =
                new AtomicReference<>(LlmPromptCacheUsage.unknown());
        private final AtomicBoolean recorded = new AtomicBoolean();

        UsageRecording(TianshuEnvelope envelope) {
            this.sourceId = clean(envelope.header().sourceId());
        }

        /** Per-request hook the inference client reports this request's cache split through. */
        Consumer<LlmPromptCacheUsage> cacheSink() {
            return cacheUsage::set;
        }

        void record(LLMPromptResultPayload.TokenUsagePayload usage) {
            if (usage == null || !recorded.compareAndSet(false, true)) {
                return;
            }
            LlmPromptCacheUsage reported = cacheUsage.get();
            boolean known = reported != null && reported.known();
            long input = usage.promptTokens();
            // An unreported split is passed on as unreported, not as a measured zero: the ledger must
            // be able to say "no data" instead of rendering a 0% hit rate. Money is unaffected by the
            // gap — pricing derives the uncached quantity from the input total, so a silent provider
            // is still billed as a full miss (never below it).
            long cacheHit = known ? reported.cacheHitTokens() : 0L;
            long cacheMiss = known ? reported.cacheMissTokens() : 0L;
            usageLedger.record(sourceId, input, cacheHit, cacheMiss, usage.outputTokens(), known);
        }
    }
}
