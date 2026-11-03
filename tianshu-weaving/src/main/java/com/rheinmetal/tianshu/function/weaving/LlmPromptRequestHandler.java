package com.rheinmetal.tianshu.function.weaving;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.function.weaving.service.LLMService;
import com.rheinmetal.tianshu.function.weaving.service.LlmPrompt;
import com.rheinmetal.tianshu.function.weaving.service.LlmPromptFactory;
import com.rheinmetal.tianshu.function.weaving.usage.LlmPromptCacheUsage;
import com.rheinmetal.tianshu.function.weaving.usage.LlmUsageLedger;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Executes one {@code LLM.REQUEST}: validates the payload, turns it into a prompt, and publishes
 * either a stream or a single result.
 *
 * <p>There is no lane, no admission queue and no background-task variant: a request is independent
 * of every other request, and concurrency is bounded by the protocol runtime and the provider, not
 * by this module.</p>
 */
final class LlmPromptRequestHandler {
    private final LlmProtocolAdapter adapter;
    private final LlmPromptFactory promptFactory;
    private final LlmUsageLedger usageLedger;

    LlmPromptRequestHandler(
            LlmProtocolAdapter adapter,
            LlmPromptFactory promptFactory,
            LlmUsageLedger usageLedger
    ) {
        this.adapter = adapter;
        this.promptFactory = promptFactory;
        this.usageLedger = usageLedger;
    }

    void handle(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope == null || !(envelope.payload() instanceof LLMPromptRequestPayload payload)) {
            complete(context, envelope);
            return;
        }
        LLMService service = adapter.currentLlmService();
        if (service == null) {
            reject(envelope, payload, context, "LLM_SERVICE_NOT_READY", "LLM service is not initialized");
            return;
        }
        UsageRecording usage = new UsageRecording(envelope);
        try {
            LlmPrompt prompt = promptFactory.create(payload);
            if (payload.stream()) {
                handleStream(service, envelope, prompt, payload, context, usage);
            } else {
                handleBlocking(service, envelope, prompt, payload, context, usage);
            }
        } catch (Exception exception) {
            reject(envelope, payload, context, "LLM_REQUEST_FAILED", failureMessage(exception), exception);
        }
    }

    private void handleBlocking(
            LLMService service,
            TianshuEnvelope envelope,
            LlmPrompt prompt,
            LLMPromptRequestPayload payload,
            ProtocolContext context,
            UsageRecording usage
    ) {
        try {
            LLMService.LLMResult result = service.chat(prompt, usage.cacheSink());
            usage.record(result.usage());
            adapter.respondLLMPromptResult(envelope, LLMPromptResultPayload.completed(
                    payload.requestId(),
                    result.text(),
                    result.thinkingContent(),
                    result.usage()
            ));
            complete(context, envelope);
        } catch (Exception exception) {
            Throwable cause = unwrapCompletion(exception);
            if (isCancellation(cause)) {
                adapter.respondLLMPromptResult(envelope, LLMPromptResultPayload.cancelled(
                        payload.requestId(), "", "", LLMPromptResultPayload.TokenUsagePayload.empty()
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

    private void handleStream(
            LLMService service,
            TianshuEnvelope envelope,
            LlmPrompt prompt,
            LLMPromptRequestPayload payload,
            ProtocolContext context,
            UsageRecording usage
    ) {
        LlmStreamState state = new LlmStreamState(payload.requestId(),
                chunk -> adapter.publishLLMPromptStreamChunk(envelope, chunk));
        try {
            LLMService.LLMStreamResult result = service.chatStream(
                    prompt,
                    state::onVisible,
                    state::onThinking,
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
            complete(context, envelope);
        } catch (Exception exception) {
            Throwable cause = unwrapCompletion(exception);
            boolean cancelled = isCancellation(cause);
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
                        LLMPromptResultPayload.TokenUsagePayload.empty()
                ));
                cancel(context, envelope, cancellationReasonCode(cause), failureMessage(cause));
                return;
            }
            adapter.respondLLMPromptResult(envelope, LLMPromptResultPayload.failed(
                    payload.requestId(),
                    "LLM_INFERENCE_FAILED",
                    failureMessage(cause),
                    terminal.visibleText(),
                    terminal.thinkingContent(),
                    LLMPromptResultPayload.TokenUsagePayload.empty()
            ));
            fail(context, envelope, "LLM_INFERENCE_FAILED", failureMessage(cause), cause);
        }
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

    private static boolean isCancellation(Throwable throwable) {
        Throwable current = unwrapCompletion(throwable);
        if (current instanceof CancellationException || current instanceof InterruptedException) {
            return true;
        }
        String message = current == null ? "" : String.valueOf(current.getMessage()).toLowerCase();
        return message.contains("cancel") || message.contains("interrupt");
    }

    private static String cancellationReasonCode(Throwable throwable) {
        Throwable current = unwrapCompletion(throwable);
        String message = current == null ? "" : String.valueOf(current.getMessage()).toLowerCase();
        if (current instanceof InterruptedException || message.contains("interrupt")) {
            return "LLM_REQUEST_INTERRUPTED";
        }
        return "LLM_REQUEST_CANCELLED";
    }

    private static String failureMessage(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
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
                    requestId, terminal.visibleText(), terminal.thinkingContent(), usage
            );
        }
        if ("FAILED".equals(type)) {
            return LLMPromptResultPayload.failed(
                    requestId,
                    "LLM_INFERENCE_FAILED",
                    failureMessage(finish.error()),
                    terminal.visibleText(),
                    terminal.thinkingContent(),
                    usage
            );
        }
        return LLMPromptResultPayload.completed(
                requestId, terminal.visibleText(), terminal.thinkingContent(), usage
        );
    }

    /**
     * Accounts for exactly one finished request.
     *
     * <p>Attribution is the envelope's {@code sourceId}: every request names the module that issued
     * it, so per-module figures need no separate instrumentation layer. The provider reports the
     * prompt-cache split only inside the inference client, so this instance is handed out as a sink
     * before the call and read back afterwards; a request that finishes through two callbacks is
     * still recorded once.</p>
     */
    private final class UsageRecording {
        private final String sourceId;
        private final AtomicReference<LlmPromptCacheUsage> cacheUsage =
                new AtomicReference<>(LlmPromptCacheUsage.unknown());
        private final AtomicBoolean recorded = new AtomicBoolean();

        UsageRecording(TianshuEnvelope envelope) {
            this.sourceId = envelope == null || envelope.header() == null
                    ? ""
                    : clean(envelope.header().sourceId());
        }

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
            long cacheHit = known ? reported.cacheHitTokens() : 0L;
            long cacheMiss = known ? reported.cacheMissTokens() : 0L;
            usageLedger.record(sourceId, input, cacheHit, cacheMiss, usage.outputTokens(), known);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
