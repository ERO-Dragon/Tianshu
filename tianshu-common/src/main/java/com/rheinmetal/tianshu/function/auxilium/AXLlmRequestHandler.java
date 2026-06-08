package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;

public interface AXLlmRequestHandler {
    default void onStreamChunk(LLMPromptStreamChunkPayload payload) {
    }

    default void onCancelled(AXTurnCancellation cancellation) {
    }

    void onResult(LLMPromptResultPayload payload);
}
