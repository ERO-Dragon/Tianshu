package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.llm.service.LLMService;
import com.rheinmetal.tianshu.function.llm.service.LlmInferenceClient;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmProtocolAdapterTest {

    @Test
    void taskRequestCompletesEnvelopeOnlyAfterFutureCompletes() {
        PendingInferenceClient client = new PendingInferenceClient();
        LLMService service = LLMService.builder()
                .env(new FakeGameEnvironment())
                .inferenceClient(client)
                .usePersistentCache(false)
                .build();
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, service);
        RecordingContext context = new RecordingContext();
        TianshuEnvelope envelope = EnvelopeBuilder.requestCapability(
                        "test",
                        ProtocolCapabilities.LLM_REQUEST,
                        PayloadType.LLM_PROMPT_REQUEST,
                        new LLMPromptRequestPayload(
                                "request-1", 0, 0.7f, false, false, "TASK", 0, false,
                                List.of(LLMPromptRequestPayload.ChunkPayload.message(
                                        List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))
                                ))
                        )
                )
                .build();

        adapter.handleLLMRequest(envelope, context);
        assertEquals(0, context.completed.get());

        client.taskFuture.complete("done");
        assertEquals(1, context.completed.get());
        assertEquals(0, context.failed.get());
    }

    private static final class PendingInferenceClient implements LlmInferenceClient {
        private final CompletableFuture<String> taskFuture = new CompletableFuture<>();

        @Override public String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) { return "chat"; }
        @Override public void chatStream(List<ChatMessage> messages, SamplerConfig sampler, Consumer<String> onToken) { onToken.accept("chat"); }
        @Override public CompletableFuture<String> task(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible) { return taskFuture; }
        @Override public CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, Consumer<String> onToken) { return taskFuture; }
        @Override public float[] embed(String text) { return new float[]{1f}; }
        @Override public float[][] embed(List<String> texts) { return texts.stream().map(ignored -> new float[]{1f}).toArray(float[][]::new); }
        @Override public List<RagSearchResult> search(String queryText, List<String> texts, int topK, float threshold) { return List.of(); }
        @Override public boolean isReady() { return true; }
        @Override public boolean hasChatQueueCapacity() { return true; }
        @Override public boolean hasTaskQueueCapacity() { return true; }
    }

    private static final class RecordingContext implements ProtocolContext {
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();

        @Override public void submit(TianshuEnvelope envelope) {}
        @Override public void complete(String envelopeId) { completed.incrementAndGet(); }
        @Override public void fail(String envelopeId, String reasonCode, String message, Throwable throwable) { failed.incrementAndGet(); }
        @Override public void cancel(String envelopeId, String reasonCode, String message) {}
        @Override public boolean isCancelled(String envelopeId) { return false; }
        @Override public void onCancel(String envelopeId, Consumer<TianshuEnvelope> callback) {}
    }

    private static final class FakeGameEnvironment implements IGameEnvironment {
        @Override public void displayMessageToPlayer(String message) {}
        @Override public void executeOnMainThread(Runnable task) { task.run(); }
        @Override public Path getGameDirectory() { return Path.of("."); }
        @Override public boolean isClientSide() { return true; }
        @Override public void openFolder(Path dir) {}
        @Override public void info(String msg) {}
        @Override public void warn(String msg) {}
        @Override public void error(String msg, Throwable t) {}
    }
}
