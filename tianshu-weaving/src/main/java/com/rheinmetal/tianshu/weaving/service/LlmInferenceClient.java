package com.rheinmetal.tianshu.weaving.service;

import com.rheinmetal.tianshu.weaving.usage.LlmPromptCacheUsage;
import com.rheinmetal.tianshu.foundry.llm.LlmStreamFinish;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Generation transport: one chat call and its streaming form, plus the two facts the rest of the
 * module needs from a transport (prompt-size estimation and readiness).
 *
 * <p>Embedding and retrieval are deliberately absent: the cloud endpoint cannot embed, and both
 * live behind the local {@link EmbeddingRuntime} / RAG storage instead.</p>
 *
 * <p>A request is an {@link LlmPrompt}: messages, sampling, the reasoning tier and the tool catalogue
 * as the caller declared them. The result is {@link LlmInferenceResult} rather than the foundry's
 * generation result, because this seam knows two facts that type cannot carry — the provider stopped
 * at the completion cap, and the model asked for tools.</p>
 */
public interface LlmInferenceClient {

    LlmInferenceResult chat(LlmPrompt prompt, Consumer<LlmPromptCacheUsage> cacheSink) throws Exception;

    CompletableFuture<LlmInferenceResult> chatStream(
            LlmPrompt prompt,
            Consumer<String> onToken,
            Consumer<String> onThinking,
            Consumer<LlmStreamFinish> onFinish,
            Consumer<LlmPromptCacheUsage> cacheSink
    ) throws Exception;

    /**
     * Prompt-size estimate. A chat-completions endpoint has no tokenize-only call, so this is the
     * local estimator; the provider's reported usage calibrates it after every real request.
     */
    int countChatPromptTokens(List<LlmChatMessage> messages);

    /**
     * Configuration validity only. No probe request is sent, so starting the module never depends on
     * the network being up.
     */
    boolean isReady();
}