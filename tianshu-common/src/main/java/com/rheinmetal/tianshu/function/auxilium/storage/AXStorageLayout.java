package com.rheinmetal.tianshu.function.auxilium.storage;

import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.nio.file.Path;

public final class AXStorageLayout {
    private final Path root;

    public AXStorageLayout(ITianshuConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.root = config.getRootPath().resolve("ax").resolve("cache");
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

    public Path attachedWorldEventsFile(AXScope scope) {
        return eventsRoot(scope).resolve("attached_world_events.jsonl");
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

    public Path statsRoot(AXScope scope) {
        return worldRoot(scope).resolve("stats");
    }

    public static String safeName(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
