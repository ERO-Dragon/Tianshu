package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface LlmInferenceClient {

    String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) throws Exception;

    void chatStream(List<ChatMessage> messages, SamplerConfig sampler, Consumer<String> onToken) throws Exception;

    CompletableFuture<String> task(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible);

    CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, Consumer<String> onToken);

    float[] embed(String text) throws Exception;

    float[][] embed(List<String> texts) throws Exception;

    List<RagSearchResult> search(String queryText, List<String> texts, int topK, float threshold);

    boolean isReady();

    boolean hasChatQueueCapacity();

    boolean hasTaskQueueCapacity();

    default boolean supportsEnableThinking() {
        return false;
    }
}
