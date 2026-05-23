package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.protocol.payload.LlmTaskResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskStreamChunkPayload;

public interface AXLlmRequestHandler {
    default void onStreamChunk(LlmTaskStreamChunkPayload payload) {
    }

    void onResult(LlmTaskResultPayload payload);
}
