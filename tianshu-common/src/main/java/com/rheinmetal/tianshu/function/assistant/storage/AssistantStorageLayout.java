package com.rheinmetal.tianshu.function.assistant.storage;

import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.function.assistant.rag.AssistantRagPathResolution;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;

import java.nio.file.Path;

public final class AssistantStorageLayout {
    private final Path root;
    private volatile AssistantRagPathResolution ragPathResolution;

    public AssistantStorageLayout(ITianshuConfig config) {
        Path llmBasePath = config == null ? Path.of("config", "TianshuAIAssistant", "module", "llm") : config.getLlmBasePath();
        this.root = llmBasePath.resolve("cache").resolve("assistant");
    }

    public void updateRagPathResolution(AssistantRagPathResolution resolution) {
        if (resolution != null && resolution.valid()) {
            this.ragPathResolution = resolution;
        }
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

    public Path worldRoot(AssistantScope scope) {
        String worldId = scope == null ? "unknown_world" : scope.worldId();
        return worldsRoot().resolve(safeName(worldId));
    }

    public Path memoryRagRoot(AssistantScope scope) {
        AssistantRagPathResolution resolution = ragPathResolution;
        if (resolution != null && resolution.valid()) {
            return resolution.memoryRagRoot();
        }
        return worldRoot(scope).resolve("memory_rag");
    }

    public Path memoryRagFile(AssistantScope scope) {
        AssistantRagPathResolution resolution = ragPathResolution;
        if (resolution != null && resolution.valid()) {
            return resolution.memoriesFile();
        }
        return memoryRagRoot(scope).resolve("memories.jsonl");
    }

    public static String safeName(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
