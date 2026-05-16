package com.rheinmetal.tianshu.function.llm.inference;

import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;

import java.util.concurrent.CompletableFuture;

public record LlmInvocationHandle(String requestKey, ProtocolTaskHandle taskHandle, CompletableFuture<LlmInvocationResult> resultFuture) {
    public LlmInvocationHandle {
        requestKey = requestKey == null || requestKey.isBlank() ? "llm.invocation" : requestKey.trim();
        resultFuture = resultFuture == null ? new CompletableFuture<>() : resultFuture;
    }

    public void cancel() {
        if (taskHandle != null) {
            taskHandle.cancel("LLM invocation cancelled");
        }
        resultFuture.complete(LlmInvocationResult.cancelled(""));
    }
}
