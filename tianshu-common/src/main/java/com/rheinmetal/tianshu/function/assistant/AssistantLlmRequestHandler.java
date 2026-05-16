package com.rheinmetal.tianshu.function.assistant;

import com.rheinmetal.tianshu.protocol.payload.LlmTaskResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskStreamChunkPayload;

public interface AssistantLlmRequestHandler {
    default void onStreamChunk(LlmTaskStreamChunkPayload payload) {
    }

    void onResult(LlmTaskResultPayload payload);
}
