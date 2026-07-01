package com.rheinmetal.tianshu.function.auxilium.core.llm;

import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;

public interface AXLlmRequestHandler {
    default void onStreamChunk(LLMPromptStreamChunkPayload payload) {
    }

    default void onCancelled(AXTurnCancellation cancellation) {
    }

    void onResult(LLMPromptResultPayload payload);
}
