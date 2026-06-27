package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.AXLlmClient;
import com.rheinmetal.tianshu.function.auxilium.AXLlmPrimitiveClient;
import com.rheinmetal.tianshu.function.auxilium.AXLlmRequestHandler;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;
import com.rheinmetal.tianshu.function.auxilium.storage.AXHashing;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMRuntimeSnapshotPayload;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AXMemoryMaintenanceService {
    private static final int VECTOR_BATCH_SIZE = 64;
    private static final long TASK_STAGE_TIMEOUT_SECONDS = 120L;

    private final AXProtocolAdapter adapter;
    private final AXMemorySystem memorySystem;
    private final AXLlmClient llmClient;
    private final AXLlmPrimitiveClient primitiveClient;
    private final AXMemoryTaskPromptRepository promptRepository;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ProtocolTaskHandle currentTask;

    public AXMemoryMaintenanceService(
            AXProtocolAdapter adapter,
            AXMemorySystem memorySystem,
            AXLlmClient llmClient,
            AXLlmPrimitiveClient primitiveClient
    ) {
        this(adapter, memorySystem, llmClient, primitiveClient, null);
    }

    public AXMemoryMaintenanceService(
            AXProtocolAdapter adapter,
            AXMemorySystem memorySystem,
            AXLlmClient llmClient,
            AXLlmPrimitiveClient primitiveClient,
            AXMemoryTaskPromptRepository promptRepository
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.memorySystem = Objects.requireNonNull(memorySystem, "memorySystem");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.primitiveClient = Objects.requireNonNull(primitiveClient, "primitiveClient");
        this.promptRepository = promptRepository == null ? new AXMemoryTaskPromptRepository(null, null) : promptRepository;
    }

    public boolean requestMaintenance(AXScope scope) {
        if (scope == null || !scope.writable() || !running.compareAndSet(false, true)) {
            return false;
        }
        currentTask = adapter.submitAxTask("ax.memory.maintenance." + AXStorageSafeName.of(scope.worldId()), ExecutionLane.LONG, () -> {
            try {
                run(scope);
            } finally {
                running.set(false);
            }
        });
        boolean accepted = currentTask != null && currentTask.state() != com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState.REJECTED;
        if (!accepted) {
            running.set(false);
        }
        return accepted;
    }

    public void stop() {
        ProtocolTaskHandle task = currentTask;
        if (task != null && !task.isDone()) {
            task.cancel("AX memory maintenance stopped");
        }
        running.set(false);
    }

    private void run(AXScope scope) {
        memorySystem.ensureStorageManifest(scope);
        AXRawTurnBatch batch = memorySystem.selectCompressionBatch(scope);
        if (!batch.isEmpty()) {
            AXStmBlock stm = compress(scope, batch).join();
            if (stm != null && !stm.isEmpty()) {
                memorySystem.appendStmBlock(scope, stm);
                memorySystem.confirmRawTurnsConsumed(scope, batch);
                List<AXMemoryEvent> events = extractEvents(scope, stm).join();
                if (!events.isEmpty()) {
                    memorySystem.ensureStorageManifest(scope);
                    memorySystem.events().appendAll(scope, events);
                }
            }
        }
        rebuildMissingVectors(scope).join();
    }

    private CompletableFuture<AXStmBlock> compress(AXScope scope, AXRawTurnBatch batch) {
        CompletableFuture<AXStmBlock> future = new CompletableFuture<>();
        TianshuEnvelope envelope = llmClient.submitDetached(compressionRequest(scope, batch), new AXLlmRequestHandler() {
            @Override
            public void onResult(LLMPromptResultPayload payload) {
                if (payload == null || !payload.isCompleted() || payload.text().isBlank()) {
                    future.complete(null);
                    return;
                }
                future.complete(new AXStmBlock(
                        "",
                        "",
                        scope.worldId(),
                        System.currentTimeMillis(),
                        batch.sourceFromMillis(),
                        batch.sourceToMillis(),
                        "",
                        "",
                        batch.turns().size(),
                        0,
                        payload.text(),
                        List.of()
                ));
            }

            @Override
            public void onCancelled(com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation cancellation) {
                future.complete(null);
            }
        });
        return withTaskTimeout(future, envelope, null);
    }

    private CompletableFuture<List<AXMemoryEvent>> extractEvents(AXScope scope, AXStmBlock stm) {
        CompletableFuture<List<AXMemoryEvent>> future = new CompletableFuture<>();
        TianshuEnvelope envelope = llmClient.submitDetached(extractionRequest(stm), new AXLlmRequestHandler() {
            @Override
            public void onResult(LLMPromptResultPayload payload) {
                if (payload == null || !payload.isCompleted() || payload.text().isBlank()) {
                    future.complete(List.of());
                    return;
                }
                future.complete(parseFacts(scope, stm, payload.text()));
            }

            @Override
            public void onCancelled(com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation cancellation) {
                future.complete(List.of());
            }
        });
        return withTaskTimeout(future, envelope, List.of());
    }

    private <T> CompletableFuture<T> withTaskTimeout(CompletableFuture<T> future, TianshuEnvelope envelope, T fallback) {
        future.orTimeout(TASK_STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(error -> {
                    if (envelope != null) {
                        llmClient.cancelRequest(envelope.envelopeId(), AXTurnCancellation.expired("AX memory task timed out"));
                    }
                    return fallback;
                });
        return future.exceptionally(error -> fallback);
    }

    private CompletableFuture<Void> rebuildMissingVectors(AXScope scope) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        primitiveClient.requestStatus("ax.memory.embedding.status", status -> {
            if (!completed(status) || !embeddingUsable(status.runtimeSnapshot())) {
                done.complete(null);
                return;
            }
            String namespace = status.runtimeSnapshot().embeddingNamespace();
            List<AXMemoryEvent> missing = missingVectorEvents(scope, namespace);
            if (missing.isEmpty()) {
                done.complete(null);
                return;
            }
            embedBatches(scope, namespace, status.runtimeSnapshot().embeddingModelName(), missing, 0, done);
        });
        return done;
    }

    private void embedBatches(AXScope scope, String namespace, String modelName, List<AXMemoryEvent> events, int from, CompletableFuture<Void> done) {
        if (from >= events.size()) {
            done.complete(null);
            return;
        }
        int to = Math.min(events.size(), from + VECTOR_BATCH_SIZE);
        List<AXMemoryEvent> batch = events.subList(from, to);
        primitiveClient.requestEmbedding("ax.memory.embedding.batch." + from, batch.stream().map(AXMemoryEvent::fact).toList(), result -> {
            if (completed(result)) {
                List<AXEventVector> vectors = toVectors(batch, result, modelName, namespace);
                if (!vectors.isEmpty()) {
                    memorySystem.ensureStorageManifest(scope);
                    memorySystem.vectors().appendAll(scope, vectors);
                }
            }
            embedBatches(scope, namespace, modelName, events, to, done);
        });
    }

    private List<AXMemoryEvent> missingVectorEvents(AXScope scope, String namespace) {
        List<AXMemoryEvent> events = memorySystem.events().loadAll(scope);
        if (events.isEmpty()) {
            return List.of();
        }
        Set<String> existing = new HashSet<>();
        for (AXEventVector vector : memorySystem.vectors().load(scope, namespace)) {
            existing.add(vector.eventId() + "\n" + vector.eventFactHash());
        }
        return events.stream()
                .filter(event -> !existing.contains(event.id() + "\n" + event.factHash()))
                .toList();
    }

    private LLMPromptRequestPayload compressionRequest(AXScope scope, AXRawTurnBatch batch) {
        StringBuilder turns = new StringBuilder();
        for (AXRawTurn turn : batch.turns()) {
            turns.append(turn.role()).append(": ").append(turn.content()).append('\n');
        }
        List<LLMPromptRequestPayload.MessageItemPayload> messages = List.of(
                LLMPromptRequestPayload.MessageItemPayload.system(promptRepository.compressionSystemPrompt()),
                LLMPromptRequestPayload.MessageItemPayload.user(promptRepository.compressionUserPrompt(scope.worldId(), turns.toString()))
        );
        return new LLMPromptRequestPayload(
                "ax.memory.compress." + batch.batchId(),
                512,
                0.2f,
                false,
                false,
                "TASK",
                600,
                true,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(messages))
        );
    }

    private LLMPromptRequestPayload extractionRequest(AXStmBlock stm) {
        List<LLMPromptRequestPayload.MessageItemPayload> messages = List.of(
                LLMPromptRequestPayload.MessageItemPayload.system(promptRepository.extractionSystemPrompt()),
                LLMPromptRequestPayload.MessageItemPayload.user(promptRepository.extractionUserPrompt(stm.content()))
        );
        return new LLMPromptRequestPayload(
                "ax.memory.extract." + stm.id(),
                512,
                0.1f,
                false,
                false,
                "TASK",
                500,
                true,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(messages))
        );
    }

    private List<AXMemoryEvent> parseFacts(AXScope scope, AXStmBlock stm, String text) {
        List<AXMemoryEvent> events = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : text.split("\\R")) {
            String fact = normalizeFactLine(line);
            if (fact.isBlank() || !seen.add(AXHashing.sha256Short(fact))) {
                continue;
            }
            events.add(new AXMemoryEvent(
                    "",
                    fact,
                    "",
                    stm.id(),
                    "stm_fact",
                    scope.worldId(),
                    "",
                    "",
                    false,
                    System.currentTimeMillis(),
                    stm.sourceToMillis(),
                    0,
                    List.of()
            ));
        }
        return events;
    }

    private String normalizeFactLine(String line) {
        if (line == null) {
            return "";
        }
        String value = line.trim();
        while (value.startsWith("-") || value.startsWith("*") || value.startsWith("\u2022")) {
            value = value.substring(1).trim();
        }
        int dot = value.indexOf('.');
        if (dot > 0 && dot <= 3) {
            String prefix = value.substring(0, dot);
            if (prefix.chars().allMatch(Character::isDigit)) {
                value = value.substring(dot + 1).trim();
            }
        }
        return value;
    }

    private List<AXEventVector> toVectors(List<AXMemoryEvent> events, LLMPrimitiveResultPayload result, String fallbackModelName, String fallbackNamespace) {
        List<AXEventVector> vectors = new ArrayList<>();
        List<LLMPrimitiveResultPayload.EmbedResultPayload> embeds = result.embedResults();
        int count = Math.min(events.size(), embeds.size());
        for (int i = 0; i < count; i++) {
            AXMemoryEvent event = events.get(i);
            LLMPrimitiveResultPayload.EmbedResultPayload embed = embeds.get(i);
            if (embed == null || embed.vector().length == 0) {
                continue;
            }
            String namespace = embed.embeddingNamespace().isBlank() ? fallbackNamespace : embed.embeddingNamespace();
            String modelName = embed.embeddingModelName().isBlank() ? fallbackModelName : embed.embeddingModelName();
            vectors.add(new AXEventVector(
                    event.id(),
                    event.factHash(),
                    modelName,
                    namespace,
                    embed.dimension(),
                    embed.vector(),
                    System.currentTimeMillis()
            ));
        }
        return vectors;
    }

    private boolean completed(LLMPrimitiveResultPayload result) {
        return result != null && LLMPrimitiveResultPayload.STATUS_COMPLETED.equals(result.status());
    }

    private boolean embeddingUsable(LLMRuntimeSnapshotPayload snapshot) {
        return snapshot != null && snapshot.embeddingAvailable() && !snapshot.embeddingNamespace().isBlank();
    }

    private static final class AXStorageSafeName {
        private AXStorageSafeName() {
        }

        private static String of(String value) {
            if (value == null || value.isBlank()) {
                return "unknown";
            }
            return value.replaceAll("[^a-zA-Z0-9._-]", "_");
        }
    }
}
