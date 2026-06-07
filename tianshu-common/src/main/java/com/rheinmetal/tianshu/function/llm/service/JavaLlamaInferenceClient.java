package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class JavaLlamaInferenceClient implements LlmInferenceClient {
    private final JavaLlamaServer server;

    public JavaLlamaInferenceClient(JavaLlamaServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) throws Exception {
        return server.chat(messages, sampler, maxTokens);
    }

    @Override
    public void chatStream(List<ChatMessage> messages, SamplerConfig sampler, Consumer<String> onToken) throws Exception {
        server.chatStream(messages, sampler, onToken);
    }

    @Override
    public CompletableFuture<String> task(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible) {
        return server.task(messages, sampler, maxTokens, priority, preemptible);
    }

    @Override
    public CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, Consumer<String> onToken) {
        return server.taskStream(messages, sampler, maxTokens, priority, preemptible, onToken);
    }

    @Override
    public float[] embed(String text) throws Exception {
        return server.embed(text);
    }

    @Override
    public float[][] embed(List<String> texts) throws Exception {
        return server.embed(texts);
    }

    @Override
    public List<RagSearchResult> search(String queryText, List<String> texts, int topK, float threshold) {
        return server.search(queryText, texts, topK, threshold);
    }

    @Override
    public boolean isReady() {
        return server.isReady();
    }

    @Override
    public boolean hasChatQueueCapacity() {
        return server.hasChatQueueCapacity();
    }

    @Override
    public boolean hasTaskQueueCapacity() {
        return server.hasTaskQueueCapacity();
    }

    @Override
    public boolean supportsEnableThinking() {
        return server.supportsEnableThinking();
    }
}
