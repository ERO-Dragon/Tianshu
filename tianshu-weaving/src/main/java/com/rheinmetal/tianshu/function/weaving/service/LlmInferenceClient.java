package com.rheinmetal.tianshu.function.weaving.service;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.usage.LlmPromptCacheUsage;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.LlmGenerationResult;
import com.rheinmetal.tianshu.libs.llm.LlmStreamFinish;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Generation transport: one chat call and its streaming form, plus the two facts the rest of the
 * module needs from a transport (prompt-size estimation and readiness).
 *
 * <p>Embedding and retrieval are deliberately absent: the cloud endpoint cannot embed, and both
 * live behind the local {@link EmbeddingRuntime} / RAG storage instead.</p>
 */
public interface LlmInferenceClient {

    LlmGenerationResult chat(
            List<ChatMessage> messages,
            LLMPromptRequestPayload.SamplingPayload sampling,
            int maxTokens,
            boolean captureThinkingContent,
            Integer reasoningTier,
            Consumer<LlmPromptCacheUsage> cacheSink
    ) throws Exception;

    CompletableFuture<LlmGenerationResult> chatStream(
            List<ChatMessage> messages,
            LLMPromptRequestPayload.SamplingPayload sampling,
            int maxTokens,
            boolean captureThinkingContent,
            Integer reasoningTier,
            Consumer<String> onToken,
            Consumer<String> onThinking,
            Consumer<LlmStreamFinish> onFinish,
            Consumer<LlmPromptCacheUsage> cacheSink
    ) throws Exception;

    /**
     * Prompt-size estimate. A chat-completions endpoint has no tokenize-only call, so this is the
     * local estimator; the provider's reported usage calibrates it after every real request.
     */
    int countChatPromptTokens(List<ChatMessage> messages);

    /**
     * Configuration validity only. No probe request is sent, so starting the module never depends on
     * the network being up.
     */
    boolean isReady();
}
