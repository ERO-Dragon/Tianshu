package com.rheinmetal.tianshu.dubhe.core.llm;

import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.dubhe.DubheTurnCancellation;

public interface DubheLlmRequestHandler {
    default void onStreamChunk(LLMPromptStreamChunkPayload payload) {
    }

    default void onCancelled(DubheTurnCancellation cancellation) {
    }

    default void onCancellationResult(LLMPromptResultPayload payload) {
    }

    void onResult(LLMPromptResultPayload payload);
}
