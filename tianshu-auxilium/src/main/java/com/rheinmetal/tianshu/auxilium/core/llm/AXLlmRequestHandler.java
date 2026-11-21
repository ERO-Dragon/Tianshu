package com.rheinmetal.tianshu.auxilium.core.llm;

import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.auxilium.AXTurnCancellation;

public interface AXLlmRequestHandler {
    default void onStreamChunk(LLMPromptStreamChunkPayload payload) {
    }

    default void onCancelled(AXTurnCancellation cancellation) {
    }

    default void onCancellationResult(LLMPromptResultPayload payload) {
    }

    void onResult(LLMPromptResultPayload payload);
}
