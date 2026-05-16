package com.rheinmetal.tianshu.function.llm.inference;

public interface LlmStreamSink {
    void onChunk(String text);

    default void onRagHit(LlmRagHit hit) {
    }

    void onFinish(LlmInvocationFinishReason finishReason);

    void onError(LlmInvocationError error);
}
