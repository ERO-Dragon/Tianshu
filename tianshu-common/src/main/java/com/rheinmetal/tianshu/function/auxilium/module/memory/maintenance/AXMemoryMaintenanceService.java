package com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance;

import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmClient;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmPrimitiveClient;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmRagClient;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmRequestHandler;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSystem;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMRuntimeSnapshotPayload;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.protocol.status.ModuleStatusSeverity;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.AXMemoryRagProjectionService;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXWorldEventMemoryLinker;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXAttachedWorldEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXEventVector;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlock;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurnBatch;

public final class AXMemoryMaintenanceService {
    private static final int VECTOR_BATCH_SIZE = 64;
    private static final long TASK_STAGE_TIMEOUT_SECONDS = 120L;
    private static final long WORLD_EVENT_ATTACHMENT_GRACE_MILLIS = 30_000L;
    private static final String STATUS_KEY_STARTED = "tianshu.presence.module.ax.memory_maintenance_started";
    private static final String STATUS_KEY_COMPLETE = "tianshu.presence.module.ax.memory_maintenance_complete";
    private static final String STATUS_KEY_FAILED = "tianshu.presence.module.ax.memory_maintenance_failed";

    private final AXProtocolAdapter adapter;
    private final AXMemorySystem memorySystem;
    private final AXRecentDialogueSystem recentDialogueSystem;
    private final AXLlmClient llmClient;
    private final AXLlmPrimitiveClient primitiveClient;
    private final AXMemoryRagProjectionService ragProjectionService;
    private final AXMemoryTaskPromptRepository promptRepository;
    private final AXMemoryFactExtractionParser factExtractionParser = new AXMemoryFactExtractionParser();
    private final AXWorldEventMemoryLinker worldEventLinker = new AXWorldEventMemoryLinker(null);
    private final AXMemoryDerivedMaintenanceService derivedMaintenanceService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private volatile ProtocolTaskHandle currentTask;

    public AXMemoryMaintenanceService(
            AXProtocolAdapter adapter,
            AXMemorySystem memorySystem,
            AXRecentDialogueSystem recentDialogueSystem,
            AXLlmClient llmClient,
            AXLlmPrimitiveClient primitiveClient
    ) {
        this(adapter, memorySystem, recentDialogueSystem, llmClient, primitiveClient, null, null);
    }

    public AXMemoryMaintenanceService(
            AXProtocolAdapter adapter,
            AXMemorySystem memorySystem,
            AXRecentDialogueSystem recentDialogueSystem,
            AXLlmClient llmClient,
            AXLlmPrimitiveClient primitiveClient,
            AXMemoryTaskPromptRepository promptRepository
    ) {
        this(adapter, memorySystem, recentDialogueSystem, llmClient, primitiveClient, null, promptRepository);
    }

    public AXMemoryMaintenanceService(
            AXProtocolAdapter adapter,
            AXMemorySystem memorySystem,
            AXRecentDialogueSystem recentDialogueSystem,
            AXLlmClient llmClient,
            AXLlmPrimitiveClient primitiveClient,
            AXLlmRagClient ragClient,
            AXMemoryTaskPromptRepository promptRepository
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.memorySystem = Objects.requireNonNull(memorySystem, "memorySystem");
        this.recentDialogueSystem = recentDialogueSystem;
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.primitiveClient = Objects.requireNonNull(primitiveClient, "primitiveClient");
        this.ragProjectionService = ragClient == null ? null : new AXMemoryRagProjectionService(this.memorySystem, ragClient);
        this.promptRepository = promptRepository == null ? new AXMemoryTaskPromptRepository(null, null) : promptRepository;
        this.derivedMaintenanceService = new AXMemoryDerivedMaintenanceService(this.memorySystem);
    }

    public boolean requestMaintenance(AXScope scope) {
        if (scope == null || !scope.writable() || !running.compareAndSet(false, true)) {
            return false;
        }
        long generation = lifecycleGeneration.incrementAndGet();
        currentTask = adapter.submitAxTask("ax.memory.maintenance." + AXStorageSafeName.of(scope.worldId()), ExecutionLane.LONG, () -> {
            try {
                run(scope, generation);
            } catch (RuntimeException exception) {
                if (isCurrent(generation)) {
                    publishFailedStatus();
                }
                throw exception;
            } finally {
                if (isCurrent(generation)) {
                    running.set(false);
                }
            }
        });
        boolean accepted = currentTask != null && currentTask.state() != com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState.REJECTED;
        if (!accepted) {
            running.set(false);
        }
        return accepted;
    }

    public void stop() {
        lifecycleGeneration.incrementAndGet();
        ProtocolTaskHandle task = currentTask;
        if (task != null && !task.isDone()) {
            task.cancel("AX memory maintenance stopped");
        }
        running.set(false);
    }

    private void run(AXScope scope, long generation) {
        if (!isCurrent(generation)) {
            return;
        }
        memorySystem.ensureStorageManifest(scope);
        AXRawTurnBatch batch = recentDialogueSystem == null ? AXRawTurnBatch.empty() : recentDialogueSystem.selectCompressionBatch(scope);
        AtomicBoolean startedStatusPublished = new AtomicBoolean(false);
        boolean compressed = false;
        if (!batch.isEmpty()) {
            publishStartedStatus(startedStatusPublished);
            List<AXAttachedWorldEvent> attachedWorldEvents = memorySystem.unattachedWorldEventsInRange(
                    scope,
                    batch.sourceFromMillis(),
                    batch.sourceToMillis() + WORLD_EVENT_ATTACHMENT_GRACE_MILLIS
            );
            AXStmBlock stm = existingStmFor(scope, batch)
                    .orElseGet(() -> compress(scope, batch, attachedWorldEvents).join());
            if (!isCurrent(generation)) {
                return;
            }
            if (stm != null && !stm.isEmpty()) {
                if (existingStmFor(scope, batch).isEmpty()) {
                    if (!memorySystem.appendStmBlock(scope, stm)) {
                        return;
                    }
                    stm = existingStmFor(scope, batch).orElse(null);
                    if (stm == null || stm.isEmpty()) {
                        return;
                    }
                }
                compressed = true;
                if (!hasEventsForStm(scope, stm)) {
                    List<AXMemoryEvent> events = new ArrayList<>(extractEvents(scope, stm).join());
                    if (!isCurrent(generation)) {
                        return;
                    }
                    events.addAll(worldEventLinker.directEventsFor(stm, attachedWorldEvents));
                    if (!events.isEmpty()) {
                        memorySystem.ensureStorageManifest(scope);
                        if (!memorySystem.events().appendAll(scope, events).success()) {
                            return;
                        }
                    }
                }
                if (recentDialogueSystem != null) {
                    recentDialogueSystem.confirmConsumed(scope, batch);
                }
            }
        }
        if (!isCurrent(generation)) {
            return;
        }
        VectorRebuildResult vectorResult = rebuildMissingVectors(scope, startedStatusPublished, generation).join();
        if (!isCurrent(generation)) {
            return;
        }
        int rebuiltVectors = vectorResult.rebuiltVectors();
        int projectedEntries = projectMemoryRag(scope, vectorResult.embeddingNamespace(), startedStatusPublished, generation).join();
        if (!isCurrent(generation)) {
            return;
        }
        AXMemoryDerivedMaintenanceResult derivedResult = derivedMaintenanceService.maintain(scope);
        if (isCurrent(generation)
                && (compressed || rebuiltVectors > 0 || projectedEntries > 0 || (derivedResult.ran() && derivedResult.stmChainRewritten()))) {
            publishCompleteStatus();
        }
    }

    private CompletableFuture<AXStmBlock> compress(AXScope scope, AXRawTurnBatch batch, List<AXAttachedWorldEvent> attachedWorldEvents) {
        CompletableFuture<AXStmBlock> future = new CompletableFuture<>();
        TianshuEnvelope envelope = llmClient.submitDetached(compressionRequest(scope, batch), new AXLlmRequestHandler() {
            @Override
            public void onResult(LLMPromptResultPayload payload) {
                String text = cleanModelTaskText(payload == null ? "" : payload.text());
                if (payload == null || !payload.isCompleted() || text.isBlank()) {
                    future.complete(null);
                    return;
                }
                future.complete(new AXStmBlock(
                        batch.stmId(),
                        "",
                        scope.worldId(),
                        System.currentTimeMillis(),
                        batch.sourceFromMillis(),
                        batch.sourceToMillis(),
                        "",
                        "",
                        batch.turns().size(),
                        completionTokenCount(payload, text),
                        text,
                        worldEventLinker.attachedEventIds(attachedWorldEvents)
                ));
            }

            @Override
            public void onCancelled(com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation cancellation) {
                future.complete(null);
            }
        });
        return withTaskTimeout(future, envelope, null);
    }

    private Optional<AXStmBlock> existingStmFor(AXScope scope, AXRawTurnBatch batch) {
        if (batch == null || batch.isEmpty() || batch.stmId().isBlank()) {
            return Optional.empty();
        }
        return memorySystem.stmBlocks().loadAll(scope).stream()
                .filter(block -> batch.stmId().equals(block.id()))
                .findFirst();
    }

    private boolean hasEventsForStm(AXScope scope, AXStmBlock stm) {
        if (stm == null || stm.id().isBlank()) {
            return false;
        }
        return memorySystem.events().loadAll(scope).stream()
                .anyMatch(event -> stm.id().equals(event.stmId()));
    }

    private CompletableFuture<List<AXMemoryEvent>> extractEvents(AXScope scope, AXStmBlock stm) {
        CompletableFuture<List<AXMemoryEvent>> future = new CompletableFuture<>();
        TianshuEnvelope envelope = llmClient.submitDetached(extractionRequest(stm), new AXLlmRequestHandler() {
            @Override
            public void onResult(LLMPromptResultPayload payload) {
                String text = cleanModelTaskText(payload == null ? "" : payload.text());
                if (payload == null || !payload.isCompleted() || text.isBlank()) {
                    future.complete(List.of());
                    return;
                }
                future.complete(parseFacts(scope, stm, text));
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

    private CompletableFuture<VectorRebuildResult> rebuildMissingVectors(AXScope scope, AtomicBoolean startedStatusPublished, long generation) {
        CompletableFuture<VectorRebuildResult> done = new CompletableFuture<>();
        primitiveClient.requestStatus("ax.memory.embedding.status", status -> {
            if (!isCurrent(generation)) {
                done.complete(VectorRebuildResult.none());
                return;
            }
            if (!completed(status) || !embeddingUsable(status.runtimeSnapshot())) {
                done.complete(VectorRebuildResult.none());
                return;
            }
            String namespace = status.runtimeSnapshot().embeddingNamespace();
            List<AXMemoryEvent> missing = missingVectorEvents(scope, namespace);
            if (missing.isEmpty()) {
                done.complete(new VectorRebuildResult(namespace, 0));
                return;
            }
            publishStartedStatus(startedStatusPublished);
            embedBatches(scope, namespace, status.runtimeSnapshot().embeddingModelName(), missing, 0, 0, done, generation);
        });
        return done;
    }

    private void embedBatches(AXScope scope, String namespace, String modelName, List<AXMemoryEvent> events, int from, int written, CompletableFuture<VectorRebuildResult> done, long generation) {
        if (!isCurrent(generation)) {
            done.complete(new VectorRebuildResult(namespace, written));
            return;
        }
        if (from >= events.size()) {
            done.complete(new VectorRebuildResult(namespace, written));
            return;
        }
        int to = Math.min(events.size(), from + VECTOR_BATCH_SIZE);
        List<AXMemoryEvent> batch = events.subList(from, to);
        primitiveClient.requestEmbedding("ax.memory.embedding.batch." + from, batch.stream().map(AXMemoryEvent::fact).toList(), result -> {
            if (!isCurrent(generation)) {
                done.complete(new VectorRebuildResult(namespace, written));
                return;
            }
            int nextWritten = written;
            if (completed(result)) {
                List<AXEventVector> vectors = toVectors(batch, result, modelName, namespace);
                if (!vectors.isEmpty()) {
                    memorySystem.ensureStorageManifest(scope);
                    memorySystem.vectors().appendAll(scope, vectors);
                    nextWritten += vectors.size();
                }
            }
            embedBatches(scope, namespace, modelName, events, to, nextWritten, done, generation);
        });
    }

    private CompletableFuture<Integer> projectMemoryRag(AXScope scope, String embeddingNamespace, AtomicBoolean startedStatusPublished, long generation) {
        if (!isCurrent(generation) || ragProjectionService == null || embeddingNamespace == null || embeddingNamespace.isBlank()) {
            return CompletableFuture.completedFuture(0);
        }
        publishStartedStatus(startedStatusPublished);
        memorySystem.invalidateRetrievalIndex(scope);
        return ragProjectionService.project(scope, embeddingNamespace)
                .thenApply(projected -> isCurrent(generation) ? projected : 0)
                .exceptionally(ignored -> 0);
    }

    private boolean isCurrent(long generation) {
        return generation > 0L && lifecycleGeneration.get() == generation;
    }

    private void publishStartedStatus(AtomicBoolean published) {
        if (published != null && !published.compareAndSet(false, true)) {
            return;
        }
        adapter.publishModuleStatus(ModuleStatus.keyed(
                AXProtocolAdapter.MODULE_ID,
                "memory.maintenance",
                STATUS_KEY_STARTED,
                ModuleStatusSeverity.NOTICE,
                3_000L,
                Map.of("presenceStatusType", "COMPRESSING")
        ));
    }

    private void publishCompleteStatus() {
        adapter.publishModuleStatus(ModuleStatuses.readyKeyed(
                AXProtocolAdapter.MODULE_ID,
                STATUS_KEY_COMPLETE
        ));
    }

    private void publishFailedStatus() {
        adapter.publishModuleStatus(ModuleStatuses.failedKeyed(
                AXProtocolAdapter.MODULE_ID,
                STATUS_KEY_FAILED
        ));
    }

    public AXRecentDialogueSystem recentDialogueSystem() {
        return recentDialogueSystem;
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
            String line = promptRepository.rawTurnLine(turn);
            if (!line.isBlank()) {
                turns.append(line).append('\n');
            }
        }
        List<LLMPromptRequestPayload.MessageItemPayload> messages = List.of(
                LLMPromptRequestPayload.MessageItemPayload.system(promptRepository.compressionSystemPrompt()),
                LLMPromptRequestPayload.MessageItemPayload.user(promptRepository.compressionUserPrompt(scope.worldId(), turns.toString()))
        );
        return new LLMPromptRequestPayload(
                "ax.memory.compress." + batch.batchId(),
                0,
                null,
                false,
                true,
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
                0,
                null,
                false,
                true,
                "TASK",
                500,
                true,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(messages))
        );
    }

    private List<AXMemoryEvent> parseFacts(AXScope scope, AXStmBlock stm, String text) {
        List<AXMemoryEvent> events = new ArrayList<>();
        for (String fact : factExtractionParser.parse(text)) {
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
                    List.of()
            ));
        }
        return events;
    }

    private int completionTokenCount(LLMPromptResultPayload payload, String text) {
        int completionTokens = payload == null || payload.usage() == null ? 0 : payload.usage().completionTokens();
        if (completionTokens > 0) {
            return completionTokens;
        }
        OptionalInt counted = primitiveClient.countTokens("ax.memory.stm.token", text);
        return counted.orElse(0);
    }

    private String cleanModelTaskText(String text) {
        return text == null ? "" : text.strip();
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

    private record VectorRebuildResult(String embeddingNamespace, int rebuiltVectors) {
        private VectorRebuildResult {
            embeddingNamespace = embeddingNamespace == null ? "" : embeddingNamespace.trim();
            rebuiltVectors = Math.max(0, rebuiltVectors);
        }

        private static VectorRebuildResult none() {
            return new VectorRebuildResult("", 0);
        }
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
