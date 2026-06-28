package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class AXMemorySystem {
    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;
    private final AXMemoryWindowPolicy windowPolicy;
    private final AXRawTurnWindow rawTurnWindow;
    private final AXStmBlockStore stmBlockStore;
    private final AXMemoryEventStore eventStore;
    private final AXAttachedWorldEventStore attachedWorldEventStore;
    private final AXEventVectorStore vectorStore;
    private final AXMemoryStatsStore statsStore;
    private final AXMemoryStorageManifestStore manifestStore;
    private final AXMemoryRetrievalIndexSnapshotStore retrievalIndexSnapshotStore;
    private final AXMemoryStorageCompatibilityChecker compatibilityChecker;
    private final AXMemoryRetrievalIndexCache retrievalIndexCache = new AXMemoryRetrievalIndexCache();
    private final AXMemoryRetrievalPolicy retrievalPolicy;

    public AXMemorySystem(AXStorageLayout layout, AXJsonStore jsonStore) {
        this(layout, jsonStore, null);
    }

    public AXMemorySystem(AXStorageLayout layout, AXJsonStore jsonStore, AXMemoryWindowPolicy policy) {
        this(layout, jsonStore, policy, AXMemoryRetrievalPolicy.DEFAULT);
    }

    public AXMemorySystem(
            AXStorageLayout layout,
            AXJsonStore jsonStore,
            AXMemoryWindowPolicy policy,
            AXMemoryRetrievalPolicy retrievalPolicy
    ) {
        this.layout = layout;
        this.jsonStore = jsonStore;
        this.windowPolicy = policy == null ? AXMemoryWindowPolicy.DEFAULT : policy;
        this.rawTurnWindow = new AXRawTurnWindow(this.windowPolicy);
        this.stmBlockStore = new AXStmBlockStore(layout, jsonStore);
        this.eventStore = new AXMemoryEventStore(layout, jsonStore);
        this.attachedWorldEventStore = new AXAttachedWorldEventStore(layout, jsonStore);
        this.vectorStore = new AXEventVectorStore(layout, jsonStore);
        this.statsStore = new AXMemoryStatsStore(layout, jsonStore);
        this.manifestStore = new AXMemoryStorageManifestStore(layout, jsonStore);
        this.retrievalIndexSnapshotStore = new AXMemoryRetrievalIndexSnapshotStore(layout, jsonStore);
        this.compatibilityChecker = new AXMemoryStorageCompatibilityChecker(layout, jsonStore);
        this.retrievalPolicy = retrievalPolicy == null ? AXMemoryRetrievalPolicy.DEFAULT : retrievalPolicy;
    }

    public AXMemorySnapshot load(AXScope scope) {
        AXScope effectiveScope = scope == null ? AXScope.unknown() : scope;
        if (!effectiveScope.writable()) {
            return AXMemorySnapshot.empty(effectiveScope);
        }
        return new AXMemorySnapshot(
                loadPersona(),
                List.of(),
                memoryBlockViews(effectiveScope, stmBlockStore.loadRecent(effectiveScope, windowPolicy.shortTermChatBlockLimit())),
                rawTurnWindow.recent(effectiveScope)
        );
    }

    public void appendRawTurn(AXScope scope, AXRawTurn turn) {
        if (scope == null || !scope.writable() || turn == null || turn.isEmpty()) {
            return;
        }
        rawTurnWindow.append(scope, turn);
    }

    public AXRawTurnBatch selectCompressionBatch(AXScope scope) {
        return rawTurnWindow.selectCompressionBatch(scope);
    }

    public int confirmRawTurnsConsumed(AXScope scope, AXRawTurnBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        return rawTurnWindow.confirmConsumed(scope, batch.turnIds());
    }

    public void appendStmBlock(AXScope scope, AXStmBlock block) {
        if (!storageReadyForWrite(scope)) {
            return;
        }
        stmBlockStore.append(scope, block);
    }

    public void appendMemoryEvent(AXScope scope, AXMemoryEvent event) {
        if (!storageReadyForWrite(scope)) {
            return;
        }
        eventStore.append(scope, event);
        retrievalIndexCache.invalidate(scope);
    }

    public void appendAttachedWorldEvent(AXScope scope, AXAttachedWorldEvent event) {
        if (event == null || event.isEmpty()) {
            return;
        }
        if (!storageReadyForWrite(scope)) {
            return;
        }
        attachedWorldEventStore.appendAll(scope, List.of(event));
    }

    public void appendEventVector(AXScope scope, AXEventVector vector) {
        if (!storageReadyForWrite(scope)) {
            return;
        }
        vectorStore.append(scope, vector);
        retrievalIndexCache.invalidate(scope);
    }

    public List<AXAttachedWorldEvent> unattachedWorldEventsInRange(AXScope scope, long fromMillis, long toMillis) {
        if (scope == null || !scope.writable() || fromMillis <= 0L || toMillis < fromMillis) {
            return List.of();
        }
        Set<String> attachedIds = stmBlockStore.loadAll(scope).stream()
                .flatMap(block -> block.attachedEventIds().stream())
                .collect(Collectors.toSet());
        return attachedWorldEventStore.loadAll(scope).stream()
                .filter(event -> event.happenedAtMillis() >= fromMillis && event.happenedAtMillis() <= toMillis)
                .filter(event -> !attachedIds.contains(event.id()))
                .toList();
    }

    public List<AXMemoryBlockView> memoryBlockViews(AXScope scope, List<AXStmBlock> blocks) {
        if (scope == null || !scope.writable() || blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        java.util.Map<String, String> attachedTextsById = attachedWorldEventStore.loadAll(scope).stream()
                .collect(Collectors.toMap(
                        AXAttachedWorldEvent::id,
                        AXMemorySystem::attachedText,
                        (first, second) -> first
                ));
        return blocks.stream()
                .filter(block -> block != null && !block.isEmpty())
                .map(block -> new AXMemoryBlockView(
                        block,
                        block.attachedEventIds().stream()
                                .map(attachedTextsById::get)
                                .filter(text -> text != null && !text.isBlank())
                                .toList()
                ))
                .toList();
    }

    public AXStmBlockStore stmBlocks() {
        return stmBlockStore;
    }

    public AXMemoryEventStore events() {
        return eventStore;
    }

    public AXAttachedWorldEventStore attachedWorldEvents() {
        return attachedWorldEventStore;
    }

    public AXEventVectorStore vectors() {
        return vectorStore;
    }

    public AXMemoryStatsStore stats() {
        return statsStore;
    }

    public AXMemoryRetrievalIndexSnapshotStore retrievalIndexSnapshots() {
        return retrievalIndexSnapshotStore;
    }

    AXMemoryRetrievalIndex retrievalIndex(AXScope scope, String embeddingNamespace) {
        if (scope == null || !scope.writable() || embeddingNamespace == null || embeddingNamespace.isBlank()) {
            return AXMemoryRetrievalIndex.empty(embeddingNamespace);
        }
        AXMemoryRetrievalIndexCache.SourceStamp sourceStamp = retrievalIndexSourceStamp(scope, embeddingNamespace);
        return retrievalIndexCache.get(
                scope,
                embeddingNamespace,
                sourceStamp,
                () -> buildRetrievalIndex(scope, embeddingNamespace, sourceStamp)
        );
    }

    private AXMemoryRetrievalIndex buildRetrievalIndex(
            AXScope scope,
            String embeddingNamespace,
            AXMemoryRetrievalIndexCache.SourceStamp sourceStamp
    ) {
        java.util.Optional<AXMemoryRetrievalIndexSnapshot> snapshot = retrievalIndexSnapshotStore.load(scope, embeddingNamespace)
                .filter(candidate -> candidate.matches(embeddingNamespace, sourceStamp));
        boolean reusableSnapshot = snapshot
                .map(candidate -> candidate.eventCount() > 0
                        && candidate.effectiveMappingByEventId().size() >= candidate.eventCount()
                        && !candidate.l1Clusters().isEmpty()
                        && !candidate.l2EffectiveMappings().isEmpty())
                .orElse(false);
        AXMemoryRetrievalIndex index = AXMemoryRetrievalIndex.build(
                eventStore.loadAll(scope),
                vectorStore.load(scope, embeddingNamespace),
                embeddingNamespace,
                retrievalPolicy,
                reusableSnapshot ? snapshot.orElse(null) : null
        );
        if (!reusableSnapshot && !index.isEmpty()) {
            retrievalIndexSnapshotStore.write(scope, index.toSnapshot(sourceStamp));
        }
        return index;
    }

    public AXRawTurnWindow rawTurns() {
        return rawTurnWindow;
    }

    public void ensureStorageManifest(AXScope scope) {
        ensureWorldManifest(scope);
    }

    public AXMemoryStorageCompatibilityReport checkStorageCompatibility(AXScope scope) {
        if (compatibilityChecker == null) {
            return new AXMemoryStorageCompatibilityReport(false, false, List.of(
                    AXMemoryStorageCompatibilityReport.Issue.error("AX_MEMORY_COMPATIBILITY_CHECKER_MISSING", "storage compatibility checker is not available")
            ));
        }
        return compatibilityChecker.check(scope);
    }

    private void ensureWorldManifest(AXScope scope) {
        if (manifestStore != null) {
            manifestStore.ensureWorldManifest(scope);
        }
    }

    private boolean storageReadyForWrite(AXScope scope) {
        if (scope == null || !scope.writable()) {
            return false;
        }
        if (compatibilityChecker != null) {
            AXMemoryStorageCompatibilityReport before = compatibilityChecker.check(scope);
            if (before.manifestPresent() && before.hasErrors()) {
                return false;
            }
        }
        ensureWorldManifest(scope);
        if (compatibilityChecker == null) {
            return true;
        }
        return !compatibilityChecker.check(scope).hasErrors();
    }

    private AXMemoryRetrievalIndexCache.SourceStamp retrievalIndexSourceStamp(AXScope scope, String embeddingNamespace) {
        return new AXMemoryRetrievalIndexCache.SourceStamp(
                fileSize(layout.eventsFile(scope)),
                fileModifiedAtMillis(layout.eventsFile(scope)),
                fileSize(layout.eventVectorsFile(scope, embeddingNamespace)),
                fileModifiedAtMillis(layout.eventVectorsFile(scope, embeddingNamespace))
        );
    }

    private long fileSize(Path path) {
        try {
            return path == null || !Files.isRegularFile(path) ? -1L : Files.size(path);
        } catch (Exception e) {
            return -1L;
        }
    }

    private long fileModifiedAtMillis(Path path) {
        try {
            return path == null || !Files.isRegularFile(path) ? -1L : Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return -1L;
        }
    }

    private String loadPersona() {
        if (layout == null || jsonStore == null) {
            return AXMemorySnapshot.defaultPersona(AXPromptLanguage.EN_US);
        }
        return jsonStore.readObject(layout.personaFile())
                .map(json -> json.has("persona") ? json.get("persona").getAsString() : "")
                .filter(value -> value != null && !value.isBlank())
                .orElse(AXMemorySnapshot.defaultPersona(AXPromptLanguage.EN_US));
    }

    private static String attachedText(AXAttachedWorldEvent event) {
        if (event == null || event.isEmpty()) {
            return "";
        }
        return event.text().isBlank() ? event.eventType() : event.text();
    }
}
