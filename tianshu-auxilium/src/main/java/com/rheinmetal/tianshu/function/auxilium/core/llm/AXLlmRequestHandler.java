package com.rheinmetal.tianshu.function.auxilium.core.llm;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;

public interface AXLlmRequestHandler {
    default void onStreamChunk(LLMPromptStreamChunkPayload payload) {
    }

    default void onCancelled(AXTurnCancellation cancellation) {
    }

    default void onCancellationResult(LLMPromptResultPayload payload) {
    }

    void onResult(LLMPromptResultPayload payload);
}
