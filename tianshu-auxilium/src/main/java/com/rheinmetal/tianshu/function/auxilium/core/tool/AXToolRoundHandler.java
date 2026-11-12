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

    private AXToolRoundHandler(AXToolTurn turn, AXLlmRequestHandler delivery, Submit submit) {
        this.turn = turn;
        this.delivery = delivery;
        this.submit = submit;
    }

    /**
     * Prepares a tool-enabled turn.
     *
     * @param base     the prompt the caller assembled
     * @param delivery the existing handler that speaks the final answer
     * @return the handler to submit, or {@code null} when no tool is enabled (the caller keeps its normal path)
     */
    public static AXToolRoundHandler prepare(LLMPromptRequestPayload base, AXLlmRequestHandler delivery,
                                             Submit submit) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(submit, "submit");
        List<ToolSpec> catalogue = TianshuTools.catalog(ToolCaller.agent(false), ToolSide.CLIENT);
        AXToolTurn turn = AXToolTurn.start(catalogue, base, call -> new AXToolExecutor().call(call)
                .handle((result, failure) -> {
                    if (failure == null) {
                        return result;
                    }
                    com.google.gson.JsonObject error = new com.google.gson.JsonObject();
                    error.addProperty("ok", false);
                    error.addProperty("code", "FAILED");
                    error.addProperty("message", String.valueOf(failure));
                    return error;
                })
                .join());
        if (!turn.toolsAvailable()) {
            return null;
        }
        return new AXToolRoundHandler(turn, delivery, submit);
    }

    /** The payload to send first: the catalogue plus the conversation, non-streaming. */
    public LLMPromptRequestPayload firstPayload(LLMPromptRequestPayload base) {
        return turn.payload(base.requestId(), false, base.maxTokens(), base.reasoningTier(), base.sampling());
    }

    @Override
    public void onResult(LLMPromptResultPayload payload) {
        if (payload != null && payload.isCompleted() && turn.toolsAvailable()) {
            AXToolTurn.Decision decision = turn.onReply(payload.text(), payload.requestId(), false, 0, null,
                    LLMPromptRequestPayload.SamplingPayload.empty());
            if (decision instanceof AXToolTurn.Decision.Continue continuation) {
                submit.submit(continuation.next(), this);
                return;
            }
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