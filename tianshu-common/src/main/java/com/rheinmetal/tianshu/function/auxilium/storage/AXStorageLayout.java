package com.rheinmetal.tianshu.function.auxilium.storage;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.nio.file.Path;

public final class AXStorageLayout {
    private final Path root;

    public AXStorageLayout(AXStorageConfiguration configuration) {
        if (configuration == null || configuration.storageRoot() == null) {
            throw new IllegalArgumentException("AX storage configuration is required");
        }
        this.root = configuration.storageRoot().normalize();
    }

    public Path root() {
        return root;
    }

    public Path sharedRoot() {
        return root.resolve("shared");
    }

    public Path worldsRoot() {
        return root.resolve("worlds");
    }

    public Path worldRoot(AXScope scope) {
        String worldId = scope == null ? "unknown_world" : scope.worldId();
        return worldsRoot().resolve(safeName(worldId));
    }

    public Path worldManifestFile(AXScope scope) {
        return worldRoot(scope).resolve("manifest.json");
    }

    public Path personaFile() {
        return sharedRoot().resolve("persona.json");
    }

    public Path promptsRoot() {
        return sharedRoot().resolve("prompts");
    }

    public Path memoryTaskPromptsFile() {
        return promptsRoot().resolve("memory_tasks.json");
    }

    public Path promptTextsFile() {
        return promptsRoot().resolve("prompt_texts.json");
    }

    public Path rawTurnsRoot(AXScope scope) {
        return worldRoot(scope).resolve("raw_turns");
    }

    public Path rawTurnCheckpointFile(AXScope scope) {
        return rawTurnsRoot(scope).resolve("raw_turn_checkpoint.jsonl");
    }

    public Path stmBlocksRoot(AXScope scope) {
        return worldRoot(scope).resolve("stm_blocks");
    }

    public Path stmBlocksFile(AXScope scope) {
        return stmBlocksRoot(scope).resolve("stm_blocks.jsonl");
    }

    public Path eventsRoot(AXScope scope) {
        return worldRoot(scope).resolve("events");
    }

    public Path eventsFile(AXScope scope) {
        return eventsRoot(scope).resolve("events.jsonl");
    }

    public Path vectorsRoot(AXScope scope) {
        return worldRoot(scope).resolve("vectors");
    }

    public Path eventVectorsRoot(AXScope scope, String embeddingNamespace) {
        return vectorsRoot(scope).resolve(safeName(embeddingNamespace));
    }

    public Path eventVectorsFile(AXScope scope, String embeddingNamespace) {
        return eventVectorsRoot(scope, embeddingNamespace).resolve("event_vectors.jsonl");
    }

    public Path indexesRoot(AXScope scope) {
        return worldRoot(scope).resolve("indexes");
    }

    public Path retrievalIndexRoot(AXScope scope, String embeddingNamespace) {
        return indexesRoot(scope).resolve(safeName(embeddingNamespace));
    }

    public Path retrievalIndexSnapshotFile(AXScope scope, String embeddingNamespace) {
        return retrievalIndexRoot(scope, embeddingNamespace).resolve("retrieval_index_snapshot.json");
    }

    public Path statsRoot(AXScope scope) {
        return worldRoot(scope).resolve("stats");
    }

    public Path memoryStatsFile(AXScope scope) {
        return statsRoot(scope).resolve("memory_stats.json");
    }

    public static String safeName(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
