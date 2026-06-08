package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.ia.payload.DialogueLlmUsageAuthorizationResultPayload;
import com.rheinmetal.tianshu.function.llm.service.LLMService;
import com.rheinmetal.tianshu.function.llm.service.LlmInferenceClient;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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

    @Test
    void chatRequestWithoutDialogueAuthorizationContextIsRejectedBeforeInference() {
        PendingInferenceClient client = new PendingInferenceClient();
        LLMService service = LLMService.builder()
                .env(new FakeGameEnvironment())
                .inferenceClient(client)
                .usePersistentCache(false)
                .build();
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, service);
        RecordingContext context = new RecordingContext();
        TianshuEnvelope envelope = llmEnvelope(new LLMPromptRequestPayload(
                "request-2", 0, 0.7f, false, false, "CHAT", 0, false,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(
                        List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))
                ))
        ));

        adapter.handleLLMRequest(envelope, context);

        assertEquals(0, client.chatCalls.get());
        assertEquals(0, context.completed.get());
        assertEquals(1, context.failed.get());
    }

    @Test
    void chatRequestRunsAfterDialogueAuthorizationAllowsRequester() {
        PendingInferenceClient client = new PendingInferenceClient();
        LLMService service = LLMService.builder()
                .env(new FakeGameEnvironment())
                .inferenceClient(client)
                .usePersistentCache(false)
                .build();
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        registerDialogueAuthorization(runtime, true);
        LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, service);
        RecordingContext context = new RecordingContext();
        TianshuEnvelope envelope = llmEnvelope("module.ax", new LLMPromptRequestPayload(
                "request-3", 0, 0.7f, false, false, "CHAT", 0, false,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(
                        List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))
                ))
        ).withDialogueAuthorization("session", "module.ax", "tianshu.AX", "turn"));

        adapter.handleLLMRequest(envelope, context);

        await(() -> client.chatCalls.get() == 1 && context.completed.get() == 1);
        assertEquals(1, client.chatCalls.get());
        assertEquals(1, context.completed.get());
        assertEquals(0, context.failed.get());
    }

    @Test
    void chatRequestIsRejectedWhenDialogueAuthorizationDeniesRequester() {
        PendingInferenceClient client = new PendingInferenceClient();
        LLMService service = LLMService.builder()
                .env(new FakeGameEnvironment())
                .inferenceClient(client)
                .usePersistentCache(false)
                .build();
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        registerDialogueAuthorization(runtime, false);
        LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, service);
        RecordingContext context = new RecordingContext();
        TianshuEnvelope envelope = llmEnvelope("module.ax", new LLMPromptRequestPayload(
                "request-4", 0, 0.7f, false, false, "CHAT", 0, false,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(
                        List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))
                ))
        ).withDialogueAuthorization("session", "module.ax", "tianshu.AX", "turn"));

        adapter.handleLLMRequest(envelope, context);

        await(() -> context.failed.get() == 1);
        assertEquals(0, client.chatCalls.get());
        assertEquals(0, context.completed.get());
        assertEquals(1, context.failed.get());
    }

    @Test
    void chatRequestIsRejectedWhenRequesterModuleDoesNotMatchEnvelopeSource() {
        PendingInferenceClient client = new PendingInferenceClient();
        LLMService service = LLMService.builder()
                .env(new FakeGameEnvironment())
                .inferenceClient(client)
                .usePersistentCache(false)
                .build();
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        registerDialogueAuthorization(runtime, true);
        LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, service);
        RecordingContext context = new RecordingContext();
        TianshuEnvelope envelope = llmEnvelope("module.bad", new LLMPromptRequestPayload(
                "request-5", 0, 0.7f, false, false, "CHAT", 0, false,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(
                        List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))
                ))
        ).withDialogueAuthorization("session", "module.ax", "tianshu.AX", "turn"));

        adapter.handleLLMRequest(envelope, context);

        assertEquals(0, client.chatCalls.get());
        assertEquals(0, context.completed.get());
        assertEquals(1, context.failed.get());
    }

    private void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private TianshuEnvelope llmEnvelope(LLMPromptRequestPayload payload) {
        return llmEnvelope("test", payload);
    }

    private TianshuEnvelope llmEnvelope(String sourceId, LLMPromptRequestPayload payload) {
        return EnvelopeBuilder.requestCapability(
                        sourceId,
                        ProtocolCapabilities.LLM_REQUEST,
                        PayloadType.LLM_PROMPT_REQUEST,
                        payload
                )
                .build();
    }

    private void registerDialogueAuthorization(ProtocolRuntime runtime, boolean allowed) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        CapabilityDescriptor capability = new CapabilityDescriptor(
                ProtocolCapabilities.DIALOGUE_LLM_USAGE_AUTHORIZE,
                PayloadType.DIALOGUE_LLM_USAGE_AUTHORIZATION_REQUEST,
                com.rheinmetal.tianshu.function.ia.payload.DialogueLlmUsageAuthorizationRequestPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.REQUEST),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN
        );
        ModuleDescriptor module = new ModuleDescriptor(
                "module.ia",
                List.of(capability),
                defaults.threadPolicy(),
                defaults.cancellationScope(),
                defaults.failurePolicy(),
                defaults.deliveryPolicy(),
                defaults.cancellable(),
                defaults.supportsStreaming(),
                defaults.maxConcurrency(),
                defaults.queueCapacity()
        );
        runtime.registerModule(module, (envelope, context) -> {
            runtime.submit(EnvelopeBuilder.responseTo(
                    "module.ia",
                    envelope,
                    PayloadType.DIALOGUE_LLM_USAGE_AUTHORIZATION_RESULT,
                    new DialogueLlmUsageAuthorizationResultPayload(
                            "session",
                            allowed,
                            "module.ax",
                            "tianshu.AX",
                            allowed ? "module.ax" : "module.other",
                            allowed ? "tianshu.AX" : "other",
                            allowed ? "" : "NOT_SESSION_OWNER",
                            allowed ? "" : "Requester is not dialogue session owner",
                            1_000L
                    )
            ).build());
        });
    }

    private static final class PendingInferenceClient implements LlmInferenceClient {
        private final CompletableFuture<String> taskFuture = new CompletableFuture<>();
        private final AtomicInteger chatCalls = new AtomicInteger();

        @Override public String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) { chatCalls.incrementAndGet(); return "chat"; }
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
