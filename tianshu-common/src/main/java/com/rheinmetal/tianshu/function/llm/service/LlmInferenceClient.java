package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.LlmGenerationResult;
import com.rheinmetal.tianshu.libs.llm.LlmStreamFinish;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmContextBudgetSnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmEngineCapabilitySnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationRequest;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCapabilitySnapshot;

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
}
