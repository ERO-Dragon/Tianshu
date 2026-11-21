package com.rheinmetal.tianshu.dubhe.core.tool;

import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.api.tool.ToolSpec;
import com.rheinmetal.tianshu.dubhe.DubheTurnCancellation;
import com.rheinmetal.tianshu.dubhe.core.llm.DubheLlmRequestHandler;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptStreamChunkPayload;

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
public final class DubheToolRoundHandler implements DubheLlmRequestHandler {

    /** Submits one request through the turn's LLM client. */
    @FunctionalInterface
    public interface Submit {
        void submit(LLMPromptRequestPayload payload, DubheLlmRequestHandler handler);
    }

    private final DubheToolTurn turn;
    private final DubheLlmRequestHandler delivery;
    private final Submit submit;
    private final Runnable discardHeld;
    private final boolean stream;
    private final Integer maxTokens;
    private final Integer reasoningTier;
    private final LLMPromptRequestPayload.SamplingPayload sampling;

    private DubheToolRoundHandler(DubheToolTurn turn, DubheLlmRequestHandler delivery, Submit submit, Runnable discardHeld,
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
    public static DubheToolRoundHandler prepare(LLMPromptRequestPayload base, DubheLlmRequestHandler delivery,
                                             Submit submit, Runnable discardHeld) {
        return prepare(base, delivery, submit, discardHeld, ToolCaller.agent(false));
    }

    /**
     * Prepares a tool-enabled turn for {@code caller}: its permission decides the catalogue, and each call runs
     * with the same identity so body access rules and write gating see the real caller.
     */
    public static DubheToolRoundHandler prepare(LLMPromptRequestPayload base, DubheLlmRequestHandler delivery,
                                             Submit submit, Runnable discardHeld, ToolCaller caller) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(submit, "submit");
        Objects.requireNonNull(discardHeld, "discardHeld");
        ToolCaller effective = caller == null ? ToolCaller.agent(false) : caller;
        List<ToolSpec> catalogue = TianshuTools.catalog(effective, ToolSide.CLIENT);
        DubheToolTurn turn = DubheToolTurn.start(catalogue, base, call ->
                TianshuTools.callJson(call.toolId(), call.args(), effective)
                        .exceptionally(failure -> {
                            com.google.gson.JsonObject error = new com.google.gson.JsonObject();
                            error.addProperty("ok", false);
                            error.addProperty("code", "FAILED");
                            error.addProperty("message", String.valueOf(failure));
                            return error;
                        }), effective);
        if (!turn.toolsAvailable()) {
            return null;
        }
        return new DubheToolRoundHandler(turn, delivery, submit, discardHeld, base);
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
                        if (decision instanceof DubheToolTurn.Decision.Continue continuation) {
                            // The muted stream held this reply back; the call itself must not be spoken either.
                            discardHeld.run();
                            submit.submit(continuation.next(), this);
                            return;
                        }
                        if (decision instanceof DubheToolTurn.Decision.Retry retry) {
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
    public void onCancelled(DubheTurnCancellation cancellation) {
        delivery.onCancelled(cancellation);
    }

    @Override
    public void onCancellationResult(LLMPromptResultPayload payload) {
        delivery.onCancellationResult(payload);
    }
}