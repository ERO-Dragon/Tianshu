package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class AXMemorySystem {
    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;
    private final AXRawTurnWindow rawTurnWindow;
    private final AXStmBlockStore stmBlockStore;
    private final AXMemoryEventStore eventStore;
    private final AXAttachedWorldEventStore attachedWorldEventStore;
    private final AXEventVectorStore vectorStore;
    private final AXMemoryStorageManifestStore manifestStore;

    public AXMemorySystem(AXStorageLayout layout, AXJsonStore jsonStore) {
        this(layout, jsonStore, null);
    }

    public AXMemorySystem(AXStorageLayout layout, AXJsonStore jsonStore, AXMemoryWindowPolicy policy) {
        this.layout = layout;
        this.jsonStore = jsonStore;
        this.rawTurnWindow = new AXRawTurnWindow(policy);
        this.stmBlockStore = new AXStmBlockStore(layout, jsonStore);
        this.eventStore = new AXMemoryEventStore(layout, jsonStore);
        this.attachedWorldEventStore = new AXAttachedWorldEventStore(layout, jsonStore);
        this.vectorStore = new AXEventVectorStore(layout, jsonStore);
        this.manifestStore = new AXMemoryStorageManifestStore(layout, jsonStore);
    }

    public AXMemorySnapshot load(AXScope scope) {
        AXScope effectiveScope = scope == null ? AXScope.unknown() : scope;
        if (!effectiveScope.writable()) {
            return AXMemorySnapshot.empty(effectiveScope);
        }
        return new AXMemorySnapshot(
                loadPersona(),
                memoryBlockViews(effectiveScope, stmBlockStore.loadRecent(effectiveScope, 8)),
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
        ensureWorldManifest(scope);
        stmBlockStore.append(scope, block);
    }

    public void appendMemoryEvent(AXScope scope, AXMemoryEvent event) {
        ensureWorldManifest(scope);
        eventStore.append(scope, event);
    }

    public void appendAttachedWorldEvent(AXScope scope, AXAttachedWorldEvent event) {
        if (event == null || event.isEmpty()) {
            return;
        }
        ensureWorldManifest(scope);
        attachedWorldEventStore.appendAll(scope, List.of(event));
    }

    public void appendEventVector(AXScope scope, AXEventVector vector) {
        ensureWorldManifest(scope);
        vectorStore.append(scope, vector);
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

    public AXRawTurnWindow rawTurns() {
        return rawTurnWindow;
    }

    public void ensureStorageManifest(AXScope scope) {
        ensureWorldManifest(scope);
    }

    private void ensureWorldManifest(AXScope scope) {
        if (manifestStore != null) {
            manifestStore.ensureWorldManifest(scope);
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
