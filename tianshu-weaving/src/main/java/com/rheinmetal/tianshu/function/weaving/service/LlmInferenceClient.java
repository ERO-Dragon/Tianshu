package com.rheinmetal.tianshu.function.weaving.service;

import com.rheinmetal.tianshu.function.weaving.usage.LlmPromptCacheUsage;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.LlmGenerationResult;
import com.rheinmetal.tianshu.libs.llm.LlmStreamFinish;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmContextBudgetSnapshot;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmEngineCapabilitySnapshot;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmMtpCalibrationRequest;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmMtpCalibrationResult;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmMtpCapabilitySnapshot;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface LlmInferenceClient {

    String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) throws Exception;

    String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options) throws Exception;

    LlmGenerationResult chatWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options) throws Exception;

    void chatStream(List<ChatMessage> messages, SamplerConfig sampler, Consumer<String> onToken) throws Exception;

    void chatStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options, Consumer<String> onToken) throws Exception;

    CompletableFuture<String> chatStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options, Consumer<String> onToken, Consumer<LlmStreamFinish> onFinish) throws Exception;

    CompletableFuture<String> chatStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options, Consumer<String> onToken, Consumer<String> onThinking, Consumer<LlmStreamFinish> onFinish) throws Exception;

    CompletableFuture<String> task(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible);

    CompletableFuture<String> task(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options);

    CompletableFuture<LlmGenerationResult> taskWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options);

    CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, Consumer<String> onToken);

    CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options, Consumer<String> onToken);

    CompletableFuture<LlmGenerationResult> taskStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options, Consumer<String> onToken, Consumer<LlmStreamFinish> onFinish);

    CompletableFuture<LlmGenerationResult> taskStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options, Consumer<String> onToken, Consumer<String> onThinking, Consumer<LlmStreamFinish> onFinish);

    float[] embed(String text) throws Exception;

    float[][] embed(List<String> texts) throws Exception;

    List<RagSearchResult> search(String queryText, List<String> texts, int topK, float threshold);

    int countChatPromptTokens(List<ChatMessage> messages, SamplerConfig sampler);

    default boolean supportsTokenCounting() {
        return true;
    }

    boolean isReady();

    boolean hasChatQueueCapacity();

    boolean hasTaskQueueCapacity();

    boolean hasQueueCapacity();

    int getChatQueueSize();

    int getTaskQueueSize();

    int getQueueSize();

    boolean supportsThinking();

    boolean supportsMtp();

    LlmMtpCapabilitySnapshot getMtpCapability();

    LlmEngineCapabilitySnapshot getRuntimeCapabilities();

    LlmContextBudgetSnapshot getContextBudgetPlan();

    LlmContextBudgetSnapshot getContextBudgetPlan(String lane);

    CompletableFuture<LlmMtpCalibrationResult> calibrateMtpAsync(LlmMtpCalibrationRequest request);

    /*
     * Cache-aware entry points. The provider's prompt-cache split exists only inside the client that
     * parsed the response, so a caller that needs it hands in a per-request sink. Implementations
     * that cannot report a split leave the sink untouched, which the caller reads as "not reported"
     * rather than "no cache hits".
     */

    default LlmGenerationResult chatWithUsage(
            List<ChatMessage> messages,
            SamplerConfig sampler,
            int maxTokens,
            LlmInferenceOptions options,
            Consumer<LlmPromptCacheUsage> cacheSink
    ) throws Exception {
        return chatWithUsage(messages, sampler, maxTokens, options);
    }

    default CompletableFuture<String> chatStreamWithUsage(
            List<ChatMessage> messages,
            SamplerConfig sampler,
            int maxTokens,
            LlmInferenceOptions options,
            Consumer<String> onToken,
            Consumer<String> onThinking,
            Consumer<LlmStreamFinish> onFinish,
            Consumer<LlmPromptCacheUsage> cacheSink
    ) throws Exception {
        return chatStreamWithUsage(messages, sampler, maxTokens, options, onToken, onThinking, onFinish);
    }

    default CompletableFuture<LlmGenerationResult> taskWithUsage(
            List<ChatMessage> messages,
            SamplerConfig sampler,
            int maxTokens,
            int priority,
            boolean preemptible,
            LlmInferenceOptions options,
            Consumer<LlmPromptCacheUsage> cacheSink
    ) {
        return taskWithUsage(messages, sampler, maxTokens, priority, preemptible, options);
    }

    default CompletableFuture<LlmGenerationResult> taskStreamWithUsage(
            List<ChatMessage> messages,
            SamplerConfig sampler,
            int maxTokens,
            int priority,
            boolean preemptible,
            LlmInferenceOptions options,
            Consumer<String> onToken,
            Consumer<String> onThinking,
            Consumer<LlmStreamFinish> onFinish,
            Consumer<LlmPromptCacheUsage> cacheSink
    ) {
        return taskStreamWithUsage(messages, sampler, maxTokens, priority, preemptible, options, onToken, onThinking, onFinish);
    }
}
