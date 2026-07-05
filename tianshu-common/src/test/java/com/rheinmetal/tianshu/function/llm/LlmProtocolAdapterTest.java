package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.ia.payload.DialogueLlmUsageAuthorizationResultPayload;
import com.rheinmetal.tianshu.function.llm.runtime.LlmContextBudgetSnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmEngineCapabilitySnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationRequest;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCapabilitySnapshot;
import com.rheinmetal.tianshu.function.llm.service.LLMService;
import com.rheinmetal.tianshu.function.llm.service.LlmInferenceClient;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.LlmGenerationResult;
import com.rheinmetal.tianshu.libs.llm.LlmStreamFinish;
import com.rheinmetal.tianshu.libs.llm.LlmTokenUsage;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.libs.llm.StreamFinishType;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.LLMCacheManagePayload;
import com.rheinmetal.tianshu.protocol.payload.LLMCacheManageResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmStatusPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
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
    void taskAdmissionQueuesSecondTaskUntilActiveTaskCompletes() {
        PendingInferenceClient client = new PendingInferenceClient();
        LLMService service = LLMService.builder()
                .env(new FakeGameEnvironment())
                .inferenceClient(client)
                .usePersistentCache(false)
                .build();
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, service, new LlmTaskAdmissionController(1));
        RecordingContext context = new RecordingContext();

        adapter.handleLLMRequest(llmEnvelope(taskPayload("task-active", 0)), context);
        adapter.handleLLMRequest(llmEnvelope(taskPayload("task-queued", 0)), context);

        assertEquals(1, client.taskCalls.get());
        assertEquals(0, context.completed.get());
        client.taskFuture.complete("first");

        await(() -> client.taskCalls.get() == 2);
        assertEquals(1, context.completed.get());
        client.taskFutures.get(1).complete("second");

        await(() -> context.completed.get() == 2);
        assertEquals(0, context.failed.get());
    }

    @Test
    void taskAdmissionRejectsWhenActiveAndWaitingQueueAreFull() {
        PendingInferenceClient client = new PendingInferenceClient();
        LLMService service = LLMService.builder()
                .env(new FakeGameEnvironment())
                .inferenceClient(client)
                .usePersistentCache(false)
                .build();
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, service, new LlmTaskAdmissionController(1));
        RecordingContext context = new RecordingContext();

        adapter.handleLLMRequest(llmEnvelope(taskPayload("task-active", 0)), context);
        adapter.handleLLMRequest(llmEnvelope(taskPayload("task-queued", 0)), context);
        adapter.handleLLMRequest(llmEnvelope(taskPayload("task-rejected", 0)), context);

        assertEquals(1, client.taskCalls.get());
        assertEquals(1, context.failed.get());
        assertEquals(0, context.completed.get());
    }

    @Test
    void taskAdmissionKeepsHigherPriorityWaitingTaskWhenQueueIsFull() {
        PendingInferenceClient client = new PendingInferenceClient();
        LLMService service = LLMService.builder()
                .env(new FakeGameEnvironment())
                .inferenceClient(client)
                .usePersistentCache(false)
                .build();
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, service, new LlmTaskAdmissionController(1));
        RecordingContext context = new RecordingContext();

        adapter.handleLLMRequest(llmEnvelope(taskPayload("task-active", 0)), context);
        adapter.handleLLMRequest(llmEnvelope(taskPayload("task-low", 0)), context);
        adapter.handleLLMRequest(llmEnvelope(taskPayload("task-high", 10)), context);

        assertEquals(1, client.taskCalls.get());
        assertEquals(1, context.failed.get());
        client.taskFuture.complete("first");

        await(() -> client.taskCalls.get() == 2);
        client.taskFutures.get(1).complete("third");

        await(() -> context.completed.get() == 2);
        assertEquals(1, context.failed.get());
    }

    @Test
    void taskAdmissionStartsHigherPriorityTaskImmediatelyWhenActiveTaskIsPreemptible() {
        PendingInferenceClient client = new PendingInferenceClient();
        LLMService service = LLMService.builder()
                .env(new FakeGameEnvironment())
                .inferenceClient(client)
                .usePersistentCache(false)
                .build();
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, service, new LlmTaskAdmissionController(1));
        RecordingContext context = new RecordingContext();

        adapter.handleLLMRequest(llmEnvelope(taskPayload("task-active", 0, true)), context);
        adapter.handleLLMRequest(llmEnvelope(taskPayload("task-preempting", 10, false)), context);

        assertEquals(2, client.taskCalls.get());
        assertEquals(List.of(0, 10), List.copyOf(client.taskPriorities));
        assertEquals(0, context.completed.get());
        assertEquals(0, context.failed.get());
    }

    @Test
    void taskAdmissionDoesNotPreemptNonPreemptibleActiveTask() {
        PendingInferenceClient client = new PendingInferenceClient();
        LLMService service = LLMService.builder()
                .env(new FakeGameEnvironment())
                .inferenceClient(client)
                .usePersistentCache(false)
                .build();
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, service, new LlmTaskAdmissionController(1));
        RecordingContext context = new RecordingContext();

        adapter.handleLLMRequest(llmEnvelope(taskPayload("task-active", 0, false)), context);
        adapter.handleLLMRequest(llmEnvelope(taskPayload("task-waiting", 10, false)), context);

        assertEquals(1, client.taskCalls.get());
        client.taskFuture.complete("first");

        await(() -> client.taskCalls.get() == 2);
        assertEquals(List.of(0, 10), List.copyOf(client.taskPriorities));
    }

    @Test
    void taskAdmissionAgingLetsOldWaitingTaskPreemptBeforeNewerTask() {
        PendingInferenceClient client = new PendingInferenceClient();
        LLMService service = LLMService.builder()
                .env(new FakeGameEnvironment())
                .inferenceClient(client)
                .usePersistentCache(false)
                .build();
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, service, new LlmTaskAdmissionController(2, 10));
        RecordingContext context = new RecordingContext();

        adapter.handleLLMRequest(llmEnvelope(taskPayload("task-active", 5, true)), context);
        adapter.handleLLMRequest(llmEnvelope(taskPayload("task-old-waiting", 0, false)), context);
        adapter.handleLLMRequest(llmEnvelope(taskPayload("task-newer", 4, false)), context);

        assertEquals(2, client.taskCalls.get());
        assertEquals(List.of(5, 0), List.copyOf(client.taskPriorities));
        assertEquals(0, context.failed.get());
    }

    @Test
    void taskAdmissionDoesNotDrainWaitingQueueUntilAllInFlightPreemptedTasksFinish() {
        PendingInferenceClient client = new PendingInferenceClient();
        LLMService service = LLMService.builder()
                .env(new FakeGameEnvironment())
                .inferenceClient(client)
                .usePersistentCache(false)
                .build();
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, service, new LlmTaskAdmissionController(2));
        RecordingContext context = new RecordingContext();

        adapter.handleLLMRequest(llmEnvelope(taskPayload("task-active", 0, true)), context);
        adapter.handleLLMRequest(llmEnvelope(taskPayload("task-preempting", 10, false)), context);
        adapter.handleLLMRequest(llmEnvelope(taskPayload("task-waiting", 1, false)), context);

        assertEquals(2, client.taskCalls.get());
        client.taskFutures.get(1).complete("second");

        await(() -> context.completed.get() == 1);
        assertEquals(2, client.taskCalls.get());

        client.taskFuture.complete("first");

        await(() -> client.taskCalls.get() == 3);
        assertEquals(List.of(0, 10, 1), List.copyOf(client.taskPriorities));
    }

    @Test
    void taskResponseUsesVisibleTextFromStructuredResult() {
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
                "request-think-hidden", 0, 0.7f, false, true, "TASK", 0, false,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(
                        List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))
                ))
        ));
        AtomicReference<LLMPromptResultPayload> result = registerResultCapture(runtime, envelope.envelopeId());

        adapter.handleLLMRequest(envelope, context);
        client.taskFuture.complete("visible answer");

        await(() -> result.get() != null);
        assertEquals("visible answer", result.get().text());
        assertEquals("", result.get().thinkingContent());
    }

    @Test
    void taskResponseCanCaptureThinkingContent() {
        PendingInferenceClient client = new PendingInferenceClient();
        client.nextTaskThinkingContent = "hidden reasoning";
        LLMService service = LLMService.builder()
                .env(new FakeGameEnvironment())
                .inferenceClient(client)
                .usePersistentCache(false)
                .build();
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, service);
        RecordingContext context = new RecordingContext();
        TianshuEnvelope envelope = llmEnvelope(new LLMPromptRequestPayload(
                "request-think-visible", 0, 0.7f, false, true, true, "TASK", 0, false,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(
                        List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))
                ))
        ));
        AtomicReference<LLMPromptResultPayload> result = registerResultCapture(runtime, envelope.envelopeId());

        adapter.handleLLMRequest(envelope, context);
        client.taskFuture.complete("visible answer");

        await(() -> result.get() != null);
        assertEquals("visible answer", result.get().text());
        assertEquals("hidden reasoning", result.get().thinkingContent());
    }

    @Test
    void taskStreamResponseUsesSeparatedVisibleTokens() {
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
                "request-think-stream-hidden", 0, 0.7f, true, true, "TASK", 0, false,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(
                        List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))
                ))
        ));
        AtomicReference<LLMPromptResultPayload> result = registerResultCapture(runtime, envelope.envelopeId());
        List<String> chunks = registerStreamChunkCapture(runtime, envelope.envelopeId());

        adapter.handleLLMRequest(envelope, context);
        await(() -> client.taskStreamTokens.get() != null);
        client.taskStreamTokens.get().accept("visible ");
        client.taskStreamTokens.get().accept("answer");
        client.taskFuture.complete("visible answer");

        await(() -> result.get() != null);
        assertEquals(List.of("visible ", "answer"), List.copyOf(chunks));
        assertEquals("visible answer", result.get().text());
        assertEquals("", result.get().thinkingContent());
    }

    @Test
    void taskStreamPublishesSeparatedThinkingChunksAndConsistentRequestId() {
        PendingInferenceClient client = new PendingInferenceClient();
        client.nextTaskThinkingContent = "hidden reasoning";
        LLMService service = LLMService.builder()
                .env(new FakeGameEnvironment())
                .inferenceClient(client)
                .usePersistentCache(false)
                .build();
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, service);
        RecordingContext context = new RecordingContext();
        TianshuEnvelope envelope = llmEnvelope(new LLMPromptRequestPayload(
                "request-think-stream-capture", 0, 0.7f, true, true, true, "TASK", 0, false,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(
                        List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))
                ))
        ));
        AtomicReference<LLMPromptResultPayload> result = registerResultCapture(runtime, envelope.envelopeId());
        List<LLMPromptStreamChunkPayload> chunks = registerStreamPayloadCapture(runtime, envelope.envelopeId());

        adapter.handleLLMRequest(envelope, context);
        await(() -> client.taskStreamTokens.get() != null && client.taskStreamThinking.get() != null);
        client.taskStreamTokens.get().accept("visible ");
        client.taskStreamThinking.get().accept("hidden reasoning");
        client.taskStreamTokens.get().accept("answer");
        client.taskFuture.complete("visible answer");

        await(() -> result.get() != null && chunks.stream().anyMatch(LLMPromptStreamChunkPayload::finished));
        assertEquals("visible answer", result.get().text());
        assertEquals("hidden reasoning", result.get().thinkingContent());
        assertEquals("request-think-stream-capture", chunks.get(0).requestId());
        assertEquals("visible ", chunks.get(0).text());
        assertEquals("", chunks.get(0).thinkingContent());
        assertEquals("request-think-stream-capture", chunks.get(1).requestId());
        assertEquals("", chunks.get(1).text());
        assertEquals("hidden reasoning", chunks.get(1).thinkingContent());
        assertEquals("request-think-stream-capture", chunks.get(2).requestId());
        assertEquals("answer", chunks.get(2).text());
        assertEquals("request-think-stream-capture", chunks.get(chunks.size() - 1).requestId());
        assertEquals("hidden reasoning", chunks.get(chunks.size() - 1).thinkingContent());
    }

    @Test
    void taskStreamKeepsResponseOpenAcrossTaskSuspension() {
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
                "request-task-suspend", 0, 0.7f, true, false, "TASK", 0, true,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(
                        List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))
                ))
        ));
        AtomicReference<LLMPromptResultPayload> result = registerResultCapture(runtime, envelope.envelopeId());
        List<String> chunks = registerStreamChunkCapture(runtime, envelope.envelopeId());

        adapter.handleLLMRequest(envelope, context);
        await(() -> client.taskStreamTokens.get() != null);
        client.taskStreamTokens.get().accept("before ");

        await(() -> chunks.size() == 1);
        assertEquals(List.of("before "), List.copyOf(chunks));
        assertEquals(null, result.get());
        assertEquals(0, context.completed.get());
        assertEquals(0, context.failed.get());
        assertEquals(0, context.cancelled.get());

        client.taskStreamTokens.get().accept("after");
        client.taskFuture.complete("before after");

        await(() -> result.get() != null && chunks.size() == 2);
        assertEquals(List.of("before ", "after"), List.copyOf(chunks));
        assertEquals("before after", result.get().text());
        assertEquals(1, context.completed.get());
        assertEquals(0, context.failed.get());
        assertEquals(0, context.cancelled.get());
    }

    @Test
    void taskStreamCancellationReturnsCancelledResultWithPartialText() {
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
                "request-task-cancel", 0, 0.7f, true, false, "TASK", 0, true,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(
                        List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))
                ))
        ));
        AtomicReference<LLMPromptResultPayload> result = registerResultCapture(runtime, envelope.envelopeId());
        List<String> chunks = registerStreamChunkCapture(runtime, envelope.envelopeId());

        adapter.handleLLMRequest(envelope, context);
        await(() -> client.taskStreamTokens.get() != null);
        client.taskStreamTokens.get().accept("partial ");
        client.taskFuture.completeExceptionally(new CancellationException("preempted by higher priority task"));

        await(() -> result.get() != null);
        assertEquals(List.of("partial "), List.copyOf(chunks));
        assertEquals(true, result.get().isCancelled());
        assertEquals("partial ", result.get().text());
        assertEquals(0, context.completed.get());
        assertEquals(0, context.failed.get());
        assertEquals(1, context.cancelled.get());
    }

    @Test
    void taskStreamPublishesRagHitsBeforeTokensSoCancellationKeepsMetadata() {
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
                "request-task-rag-cancel", 0, 0.7f, true, false, "TASK", 0, true,
                List.of(
                        LLMPromptRequestPayload.ChunkPayload.message(
                                List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))
                        ),
                        LLMPromptRequestPayload.ChunkPayload.rag(
                                "memory",
                                "RAG:",
                                List.of("remembered fact"),
                                true,
                                true,
                                1000
                        )
                )
        ));
        AtomicReference<LLMPromptResultPayload> result = registerResultCapture(runtime, envelope.envelopeId());
        List<LLMPromptStreamChunkPayload> streamPayloads = registerStreamPayloadCapture(runtime, envelope.envelopeId());

        adapter.handleLLMRequest(envelope, context);
        await(() -> !streamPayloads.isEmpty());
        client.taskFuture.completeExceptionally(new CancellationException("cancelled"));

        await(() -> result.get() != null);
        LLMPromptStreamChunkPayload metadata = streamPayloads.get(0);
        assertEquals("", metadata.text());
        assertEquals(1, metadata.ragHits().size());
        assertEquals("memory", metadata.ragHits().get(0).uid());
        assertEquals(true, result.get().isCancelled());
        assertEquals(1, result.get().ragHits().size());
        assertEquals("remembered fact", result.get().ragHits().get(0).hits().get(0).content());
    }

    @Test
    void taskCancellationReturnsCancelledResult() {
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
                "request-task-cancel-non-stream", 0, 0.7f, false, false, "TASK", 0, true,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(
                        List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))
                ))
        ));
        AtomicReference<LLMPromptResultPayload> result = registerResultCapture(runtime, envelope.envelopeId());

        adapter.handleLLMRequest(envelope, context);
        client.taskFuture.completeExceptionally(new CancellationException("cancelled"));

        await(() -> result.get() != null);
        assertEquals(true, result.get().isCancelled());
        assertEquals("", result.get().text());
        assertEquals(0, context.completed.get());
        assertEquals(0, context.failed.get());
        assertEquals(1, context.cancelled.get());
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

    @Test
    void inferenceStatusIsPublishedToLlmStatusTopic() {
        ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run);
        LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, null);
        List<LlmStatusPayload> statuses = registerStatusCapture(runtime);

        adapter.publishInferenceStatus(new LlmStatusPayload(
                "task-1",
                "STREAM_COMPLETION",
                "TASK",
                "COLD_RESUME_STARTED",
                7,
                "replaying",
                123,
                45,
                "",
                1_000L
        ));

        await(() -> statuses.size() == 1);
        assertEquals(1, statuses.size());
        LlmStatusPayload status = statuses.get(0);
        assertEquals("task-1", status.taskId());
        assertEquals("TASK", status.lane());
        assertEquals("COLD_RESUME_STARTED", status.eventType());
        assertEquals(123, status.replayCharacters());
        assertEquals(45, status.generatedTokens());
    }

    @Test
    void cacheManageUpsertCanAddRagEntry() {
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
                        ProtocolCapabilities.LLM_CACHE_MANAGE,
                        PayloadType.LLM_CACHE_MANAGE,
                        LLMCacheManagePayload.upsertEntry("shared", "entry-a", "memory", null)
                )
                .build();
        AtomicReference<LLMCacheManageResultPayload> result = registerCacheManageResultCapture(runtime, envelope.envelopeId());

        adapter.handleLLMCacheManage(envelope, context);

        await(() -> result.get() != null);
        assertEquals(1, context.completed.get());
        assertEquals("UPSERT_ENTRY", result.get().action());
        assertEquals(true, service.hasRagEntry("shared", "entry-a"));
        assertEquals(true, service.hasCache("shared"));
    }

    @Test
    void primitiveTokenCountUsesInferenceClientTokenizerCount() {
        PendingInferenceClient client = new PendingInferenceClient();
        client.tokenCount = 7;
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
                        ProtocolCapabilities.LLM_PRIMITIVE_QUERY,
                        PayloadType.LLM_PRIMITIVE_QUERY,
                        LLMPrimitiveQueryPayload.tokenCount(
                                "tokens-1",
                                "",
                                List.of(LLMPrimitiveQueryPayload.MessageItemPayload.user("hello")),
                                List.of()
                        )
                )
                .build();
        AtomicReference<LLMPrimitiveResultPayload> result = registerPrimitiveResultCapture(runtime, envelope.envelopeId());

        adapter.handleLLMPrimitiveQuery(envelope, context);

        await(() -> result.get() != null);
        assertEquals(1, context.completed.get());
        assertEquals(0, context.failed.get());
        assertEquals("COMPLETED", result.get().status());
        assertEquals(7, result.get().tokenCount());
        assertEquals(1, client.tokenCountCalls.get());
    }

    @Test
    void taskResultCarriesTokenUsage() {
        PendingInferenceClient client = new PendingInferenceClient();
        LLMService service = LLMService.builder()
                .env(new FakeGameEnvironment())
                .inferenceClient(client)
                .usePersistentCache(false)
                .build();
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, service);
        RecordingContext context = new RecordingContext();
        TianshuEnvelope envelope = llmEnvelope(taskPayload("task-usage", 0));
        AtomicReference<LLMPromptResultPayload> result = registerResultCapture(runtime, envelope.envelopeId());

        adapter.handleLLMRequest(envelope, context);
        client.taskFuture.complete("done");

        await(() -> result.get() != null);
        assertEquals("COMPLETED", result.get().status());
        assertEquals(1, result.get().usage().promptTokens());
        assertEquals(1, result.get().usage().completionTokens());
        assertEquals(2, result.get().usage().totalTokens());
    }

    @Test
    void taskStreamEndCarriesTerminalUsage() {
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
                "task-stream-usage", 0, 0.7f, true, false, "TASK", 0, false,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(
                        List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))
                ))
        ));
        List<LLMPromptStreamChunkPayload> chunks = registerStreamPayloadCapture(runtime, envelope.envelopeId());

        adapter.handleLLMRequest(envelope, context);
        await(() -> client.taskStreamTokens.get() != null);
        client.taskStreamTokens.get().accept("done");
        client.taskFuture.complete("done");

        await(() -> chunks.stream().anyMatch(LLMPromptStreamChunkPayload::finished));
        LLMPromptStreamChunkPayload end = chunks.stream().filter(LLMPromptStreamChunkPayload::finished).findFirst().orElseThrow();
        assertEquals("COMPLETED", end.finishType());
        assertEquals(1, end.usage().promptTokens());
        assertEquals(1, end.usage().completionTokens());
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

    private LLMPromptRequestPayload taskPayload(String requestId, int priority) {
        return taskPayload(requestId, priority, false);
    }

    private LLMPromptRequestPayload taskPayload(String requestId, int priority, boolean preemptible) {
        return new LLMPromptRequestPayload(
                requestId, 0, 0.7f, false, false, "TASK", priority, preemptible,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(
                        List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))
                ))
        );
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

    private AtomicReference<LLMPromptResultPayload> registerResultCapture(ProtocolRuntime runtime, String requestEnvelopeId) {
        AtomicReference<LLMPromptResultPayload> result = new AtomicReference<>();
        AdapterDefaults defaults = AdapterDefaults.standard();
        CapabilityDescriptor capability = new CapabilityDescriptor(
                "test.llm.response",
                PayloadType.LLM_PROMPT_RESULT,
                LLMPromptResultPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE
        );
        ModuleDescriptor module = new ModuleDescriptor(
                "module.test.response",
                List.of(),
                defaults.threadPolicy(),
                defaults.cancellationScope(),
                defaults.failurePolicy(),
                defaults.deliveryPolicy(),
                defaults.cancellable(),
                defaults.supportsStreaming(),
                defaults.maxConcurrency(),
                defaults.queueCapacity()
        );
        runtime.registerResponseHandler(requestEnvelopeId, module, capability, (envelope, context) -> {
            if (envelope.payload() instanceof LLMPromptResultPayload payload) {
                result.set(payload);
            }
            context.complete(envelope.envelopeId());
        });
        return result;
    }

    private AtomicReference<LLMCacheManageResultPayload> registerCacheManageResultCapture(ProtocolRuntime runtime, String requestEnvelopeId) {
        AtomicReference<LLMCacheManageResultPayload> result = new AtomicReference<>();
        AdapterDefaults defaults = AdapterDefaults.standard();
        CapabilityDescriptor capability = new CapabilityDescriptor(
                "test.llm.cache.response",
                PayloadType.LLM_CACHE_MANAGE_RESULT,
                LLMCacheManageResultPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE
        );
        ModuleDescriptor module = new ModuleDescriptor(
                "module.test.cache.response",
                List.of(),
                defaults.threadPolicy(),
                defaults.cancellationScope(),
                defaults.failurePolicy(),
                defaults.deliveryPolicy(),
                defaults.cancellable(),
                defaults.supportsStreaming(),
                defaults.maxConcurrency(),
                defaults.queueCapacity()
        );
        runtime.registerResponseHandler(requestEnvelopeId, module, capability, (envelope, context) -> {
            if (envelope.payload() instanceof LLMCacheManageResultPayload payload) {
                result.set(payload);
            }
            context.complete(envelope.envelopeId());
        });
        return result;
    }

    private AtomicReference<LLMPrimitiveResultPayload> registerPrimitiveResultCapture(ProtocolRuntime runtime, String requestEnvelopeId) {
        AtomicReference<LLMPrimitiveResultPayload> result = new AtomicReference<>();
        AdapterDefaults defaults = AdapterDefaults.standard();
        CapabilityDescriptor capability = new CapabilityDescriptor(
                "test.llm.primitive.response",
                PayloadType.LLM_PRIMITIVE_RESULT,
                LLMPrimitiveResultPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE
        );
        ModuleDescriptor module = new ModuleDescriptor(
                "module.test.primitive.response",
                List.of(),
                defaults.threadPolicy(),
                defaults.cancellationScope(),
                defaults.failurePolicy(),
                defaults.deliveryPolicy(),
                defaults.cancellable(),
                defaults.supportsStreaming(),
                defaults.maxConcurrency(),
                defaults.queueCapacity()
        );
        runtime.registerResponseHandler(requestEnvelopeId, module, capability, (envelope, context) -> {
            if (envelope.payload() instanceof LLMPrimitiveResultPayload payload) {
                result.set(payload);
            }
            context.complete(envelope.envelopeId());
        });
        return result;
    }

    private List<String> registerStreamChunkCapture(ProtocolRuntime runtime, String requestEnvelopeId) {
        List<String> chunks = java.util.Collections.synchronizedList(new ArrayList<>());
        AdapterDefaults defaults = AdapterDefaults.standard();
        CapabilityDescriptor capability = new CapabilityDescriptor(
                "test.llm.stream.response",
                PayloadType.LLM_PROMPT_STREAM_CHUNK,
                LLMPromptStreamChunkPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE
        );
        ModuleDescriptor module = new ModuleDescriptor(
                "module.test.stream.response",
                List.of(),
                defaults.threadPolicy(),
                defaults.cancellationScope(),
                defaults.failurePolicy(),
                defaults.deliveryPolicy(),
                defaults.cancellable(),
                defaults.supportsStreaming(),
                defaults.maxConcurrency(),
                defaults.queueCapacity()
        );
        runtime.registerResponseHandler(requestEnvelopeId, module, capability, (envelope, context) -> {
            if (envelope.payload() instanceof LLMPromptStreamChunkPayload payload && !payload.finished() && !payload.text().isEmpty()) {
                synchronized (chunks) {
                    while (chunks.size() <= payload.index()) {
                        chunks.add("");
                    }
                    chunks.set(payload.index(), payload.text());
                }
            }
            context.complete(envelope.envelopeId());
        });
        return chunks;
    }

    private List<LLMPromptStreamChunkPayload> registerStreamPayloadCapture(ProtocolRuntime runtime, String requestEnvelopeId) {
        List<LLMPromptStreamChunkPayload> chunks = new CopyOnWriteArrayList<>();
        AdapterDefaults defaults = AdapterDefaults.standard();
        CapabilityDescriptor capability = new CapabilityDescriptor(
                "test.llm.stream.payload.response",
                PayloadType.LLM_PROMPT_STREAM_CHUNK,
                LLMPromptStreamChunkPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE
        );
        ModuleDescriptor module = new ModuleDescriptor(
                "module.test.stream.payload.response",
                List.of(),
                defaults.threadPolicy(),
                defaults.cancellationScope(),
                defaults.failurePolicy(),
                defaults.deliveryPolicy(),
                defaults.cancellable(),
                defaults.supportsStreaming(),
                defaults.maxConcurrency(),
                defaults.queueCapacity()
        );
        runtime.registerResponseHandler(requestEnvelopeId, module, capability, (envelope, context) -> {
            if (envelope.payload() instanceof LLMPromptStreamChunkPayload payload) {
                chunks.add(payload);
            }
            context.complete(envelope.envelopeId());
        });
        return chunks;
    }

    private List<LlmStatusPayload> registerStatusCapture(ProtocolRuntime runtime) {
        List<LlmStatusPayload> statuses = new CopyOnWriteArrayList<>();
        AdapterDefaults defaults = AdapterDefaults.standard();
        TopicSubscriptionDescriptor subscription = new TopicSubscriptionDescriptor(
                ProtocolTopics.LLM_STATUS,
                PayloadType.LLM_STATUS,
                LlmStatusPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.EVENT),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN
        );
        ModuleDescriptor module = new ModuleDescriptor(
                "module.test.llm.status",
                List.of(),
                defaults.threadPolicy(),
                defaults.cancellationScope(),
                defaults.failurePolicy(),
                defaults.deliveryPolicy(),
                defaults.cancellable(),
                defaults.supportsStreaming(),
                defaults.maxConcurrency(),
                defaults.queueCapacity()
        );
        runtime.subscribeTopic(module, subscription, (envelope, context) -> {
            if (envelope.payload() instanceof LlmStatusPayload payload) {
                statuses.add(payload);
            }
        });
        return statuses;
    }

    private static final class PendingInferenceClient implements LlmInferenceClient {
        private final CompletableFuture<String> taskFuture = new CompletableFuture<>();
        private final List<CompletableFuture<String>> taskFutures = new CopyOnWriteArrayList<>();
        private final List<Integer> taskPriorities = new CopyOnWriteArrayList<>();
        private final AtomicInteger chatCalls = new AtomicInteger();
        private final AtomicInteger taskCalls = new AtomicInteger();
        private final AtomicInteger tokenCountCalls = new AtomicInteger();
        private final AtomicReference<Consumer<String>> taskStreamTokens = new AtomicReference<>();
        private final AtomicReference<Consumer<String>> taskStreamThinking = new AtomicReference<>();
        private int tokenCount = 1;
        private String nextTaskThinkingContent = "";

        @Override public String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) { chatCalls.incrementAndGet(); return "chat"; }
        @Override public String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, com.rheinmetal.tianshu.function.llm.service.LlmInferenceOptions options) { return chat(messages, sampler, maxTokens); }
        @Override public LlmGenerationResult chatWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, com.rheinmetal.tianshu.function.llm.service.LlmInferenceOptions options) { return new LlmGenerationResult(chat(messages, sampler, maxTokens), new LlmTokenUsage(1, 1)); }
        @Override public void chatStream(List<ChatMessage> messages, SamplerConfig sampler, Consumer<String> onToken) { onToken.accept("chat"); }
        @Override public void chatStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, com.rheinmetal.tianshu.function.llm.service.LlmInferenceOptions options, Consumer<String> onToken) { chatStream(messages, sampler, onToken); }
        @Override public CompletableFuture<String> chatStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, com.rheinmetal.tianshu.function.llm.service.LlmInferenceOptions options, Consumer<String> onToken, Consumer<LlmStreamFinish> onFinish) { chatStream(messages, sampler, onToken); if (onFinish != null) onFinish.accept(new LlmStreamFinish(StreamFinishType.COMPLETED, new LlmTokenUsage(1, 1), null)); return CompletableFuture.completedFuture("chat"); }
        @Override public CompletableFuture<String> chatStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, com.rheinmetal.tianshu.function.llm.service.LlmInferenceOptions options, Consumer<String> onToken, Consumer<String> onThinking, Consumer<LlmStreamFinish> onFinish) { return chatStreamWithUsage(messages, sampler, maxTokens, options, onToken, onFinish); }
        @Override public CompletableFuture<String> task(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible) { taskCalls.incrementAndGet(); taskPriorities.add(priority); return nextTaskFuture(); }
        @Override public CompletableFuture<String> task(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, com.rheinmetal.tianshu.function.llm.service.LlmInferenceOptions options) { return task(messages, sampler, maxTokens, priority, preemptible); }
        @Override public CompletableFuture<LlmGenerationResult> taskWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, com.rheinmetal.tianshu.function.llm.service.LlmInferenceOptions options) { return task(messages, sampler, maxTokens, priority, preemptible).thenApply(text -> new LlmGenerationResult(text, new LlmTokenUsage(1, 1), Boolean.TRUE.equals(options == null ? false : options.captureThinkingContent()) ? nextTaskThinkingContent : "")); }
        @Override public CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, Consumer<String> onToken) { taskCalls.incrementAndGet(); taskPriorities.add(priority); taskStreamTokens.set(onToken); return nextTaskFuture(); }
        @Override public CompletableFuture<String> taskStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, com.rheinmetal.tianshu.function.llm.service.LlmInferenceOptions options, Consumer<String> onToken) { return taskStream(messages, sampler, maxTokens, priority, preemptible, onToken); }
        @Override public CompletableFuture<LlmGenerationResult> taskStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, com.rheinmetal.tianshu.function.llm.service.LlmInferenceOptions options, Consumer<String> onToken, Consumer<LlmStreamFinish> onFinish) { return taskStream(messages, sampler, maxTokens, priority, preemptible, onToken).thenApply(text -> { if (onFinish != null) onFinish.accept(new LlmStreamFinish(StreamFinishType.COMPLETED, new LlmTokenUsage(1, 1), null)); return new LlmGenerationResult(text, new LlmTokenUsage(1, 1)); }); }
        @Override public CompletableFuture<LlmGenerationResult> taskStreamWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, int priority, boolean preemptible, com.rheinmetal.tianshu.function.llm.service.LlmInferenceOptions options, Consumer<String> onToken, Consumer<String> onThinking, Consumer<LlmStreamFinish> onFinish) { taskStreamThinking.set(onThinking); return taskStream(messages, sampler, maxTokens, priority, preemptible, onToken).thenApply(text -> { String thinking = Boolean.TRUE.equals(options == null ? false : options.captureThinkingContent()) ? nextTaskThinkingContent : ""; if (onFinish != null) onFinish.accept(new LlmStreamFinish(StreamFinishType.COMPLETED, new LlmTokenUsage(1, 1), null, thinking)); return new LlmGenerationResult(text, new LlmTokenUsage(1, 1), thinking); }); }
        @Override public float[] embed(String text) { return new float[]{1f}; }
        @Override public float[][] embed(List<String> texts) { return texts.stream().map(ignored -> new float[]{1f}).toArray(float[][]::new); }
        @Override public List<RagSearchResult> search(String queryText, List<String> texts, int topK, float threshold) { return List.of(); }
        @Override public int countChatPromptTokens(List<ChatMessage> messages, SamplerConfig sampler) { tokenCountCalls.incrementAndGet(); return tokenCount; }
        @Override public boolean isReady() { return true; }
        @Override public boolean hasChatQueueCapacity() { return true; }
        @Override public boolean hasTaskQueueCapacity() { return true; }
        @Override public boolean hasQueueCapacity() { return true; }
        @Override public int getChatQueueSize() { return 0; }
        @Override public int getTaskQueueSize() { return taskFutures.size(); }
        @Override public int getQueueSize() { return getTaskQueueSize(); }
        @Override public boolean supportsThinking() { return true; }
        @Override public boolean supportsMtp() { return false; }
        @Override public LlmMtpCapabilitySnapshot getMtpCapability() { return LlmMtpCapabilitySnapshot.unsupported(); }
        @Override public LlmEngineCapabilitySnapshot getRuntimeCapabilities() { return new LlmEngineCapabilitySnapshot(true, true, false, false, false, 0); }
        @Override public LlmContextBudgetSnapshot getContextBudgetPlan() { return new LlmContextBudgetSnapshot(4096, 4096, 4096, 4096, 3000, 64, 0L, true, ""); }
        @Override public LlmContextBudgetSnapshot getContextBudgetPlan(String lane) { return getContextBudgetPlan(); }
        @Override public CompletableFuture<LlmMtpCalibrationResult> calibrateMtpAsync(LlmMtpCalibrationRequest request) { return CompletableFuture.completedFuture(LlmMtpCalibrationResult.unsupported()); }

        private CompletableFuture<String> nextTaskFuture() {
            if (taskFutures.isEmpty()) {
                taskFutures.add(taskFuture);
                return taskFuture;
            }
            CompletableFuture<String> future = new CompletableFuture<>();
            taskFutures.add(future);
            return future;
        }
    }

    private static final class RecordingContext implements ProtocolContext {
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicInteger cancelled = new AtomicInteger();

        @Override public void submit(TianshuEnvelope envelope) {}
        @Override public void complete(String envelopeId) { completed.incrementAndGet(); }
        @Override public void fail(String envelopeId, String reasonCode, String message, Throwable throwable) { failed.incrementAndGet(); }
        @Override public void cancel(String envelopeId, String reasonCode, String message) { cancelled.incrementAndGet(); }
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
