package com.rheinmetal.tianshu.function.auxilium.core.tool;

import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.api.tool.ToolSpec;
import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmRequestHandler;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptStreamChunkPayload;

import java.util.List;
import java.util.Objects;

/**
 * The handler the LLM client calls for tool-enabled turns: it keeps model calls inside one step and hands only the
 * final answer to the normal delivery handler.
 *
 * <p>Tool rounds use a non-streaming request. Speaking model output before the reply is known to be an answer (not a
 * call) would read the JSON aloud; a tool turn therefore trades streaming for correctness, and the final answer
 * reaches the player through the unchanged delivery path.</p>
 */
public final class AXToolRoundHandler implements AXLlmRequestHandler {

    /** Submits one request through the turn's LLM client. */
    @FunctionalInterface
    public interface Submit {
        void submit(LLMPromptRequestPayload payload, AXLlmRequestHandler handler);
    }

    private final AXToolTurn turn;
    private final AXLlmRequestHandler delivery;
    private final Submit submit;
    private final Runnable discardHeld;
    private final boolean stream;
    private final Integer maxTokens;
    private final Integer reasoningTier;
    private final LLMPromptRequestPayload.SamplingPayload sampling;

    private AXToolRoundHandler(AXToolTurn turn, AXLlmRequestHandler delivery, Submit submit, Runnable discardHeld,
                               LLMPromptRequestPayload base) {
        this.turn = turn;
        this.delivery = delivery;
        this.submit = submit;
        this.discardHeld = discardHeld;
        this.stream = base.stream();
        this.maxTokens = base.maxTokens();
        this.reasoningTier = base.reasoningTier();
        this.sampling = base.sampling();
    }

    /**
     * Prepares a tool-enabled turn.
     *
     * @param base     the prompt the caller assembled
     * @param delivery the existing handler that speaks the final answer
     * @return the handler to submit, or {@code null} when no tool is enabled (the caller keeps its normal path)
     */
    public static AXToolRoundHandler prepare(LLMPromptRequestPayload base, AXLlmRequestHandler delivery,
                                             Submit submit, Runnable discardHeld) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(submit, "submit");
        Objects.requireNonNull(discardHeld, "discardHeld");
        List<ToolSpec> catalogue = TianshuTools.catalog(ToolCaller.agent(false), ToolSide.CLIENT);
        AXToolTurn turn = AXToolTurn.start(catalogue, base, call ->
                TianshuTools.callJson(call.toolId(), call.args(), ToolCaller.agent(false))
                        .exceptionally(failure -> {
                            com.google.gson.JsonObject error = new com.google.gson.JsonObject();
                            error.addProperty("ok", false);
                            error.addProperty("code", "FAILED");
                            error.addProperty("message", String.valueOf(failure));
                            return error;
                        }));
        if (!turn.toolsAvailable()) {
            return null;
        }
        return new AXToolRoundHandler(turn, delivery, submit, discardHeld, base);
    }

    /** The payload to send first: the catalogue plus the conversation, non-streaming. */
    public LLMPromptRequestPayload firstPayload(LLMPromptRequestPayload base) {
        return turn.payload(base.requestId(), stream, maxTokens, reasoningTier, sampling);
    }

    @Override
    public void onResult(LLMPromptResultPayload payload) {
        if (payload != null && payload.isCompleted() && turn.toolsAvailable()) {
            turn.onReply(payload.text(), payload.requestId(), stream, maxTokens, reasoningTier, sampling)
                    .thenAccept(decision -> {
                        if (decision instanceof AXToolTurn.Decision.Continue continuation) {
                            // The muted stream held this reply back; the call itself must not be spoken either.
                            discardHeld.run();
                            submit.submit(continuation.next(), this);
                            return;
                        }
                        if (decision instanceof AXToolTurn.Decision.Retry retry) {
                            // A malformed call is not the final answer; one more model round may repair it.
                            discardHeld.run();
                            submit.submit(retry.next(), this);
                            return;
                        }
                        delivery.onResult(payload);
                    });
            return;
        }
        delivery.onResult(payload);
    }

    @Override
    public void onStreamChunk(LLMPromptStreamChunkPayload payload) {
        delivery.onStreamChunk(payload);
    }

    @Override
    public void onCancelled(AXTurnCancellation cancellation) {
        delivery.onCancelled(cancellation);
    }

    @Override
    public void onCancellationResult(LLMPromptResultPayload payload) {
        delivery.onCancellationResult(payload);
    }
}