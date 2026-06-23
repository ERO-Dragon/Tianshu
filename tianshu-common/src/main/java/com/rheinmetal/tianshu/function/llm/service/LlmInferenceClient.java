package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationRequest;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCapabilitySnapshot;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface LlmInferenceClient {

    String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) throws Exception;

    default String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options) throws Exception {
        return chat(messages, sampler, maxTokens);
    }

    void chatStream(List<ChatMessage> messages, SamplerConfig sampler, Consumer<String> onToken) throws Exception;

    default void chatStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, LlmInferenceOptions options, Consumer<String> onToken) throws Exception {
        chatStream(messages, sampler, onToken);
    }

    CompletableFuture<String> task(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible);

    default CompletableFuture<String> task(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options) {
        return task(messages, sampler, maxTokens, priority, preemptible);
    }

    CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, Consumer<String> onToken);

    default CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, LlmInferenceOptions options, Consumer<String> onToken) {
        return taskStream(messages, sampler, maxTokens, priority, preemptible, onToken);
    }

    float[] embed(String text) throws Exception;

    float[][] embed(List<String> texts) throws Exception;

    List<RagSearchResult> search(String queryText, List<String> texts, int topK, float threshold);

    boolean isReady();

    boolean hasChatQueueCapacity();

    boolean hasTaskQueueCapacity();

    default boolean supportsEnableThinking() {
        return false;
    }

    default boolean supportsMtp() {
        return false;
    }

    default LlmMtpCapabilitySnapshot getMtpCapability() {
        return LlmMtpCapabilitySnapshot.unsupported();
    }

    default CompletableFuture<LlmMtpCalibrationResult> calibrateMtpAsync(LlmMtpCalibrationRequest request) {
        return CompletableFuture.completedFuture(LlmMtpCalibrationResult.unsupported());
    }
}
