package com.rheinmetal.tianshu.function.auxilium.storage;

import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.function.auxilium.rag.AXRagPathResolution;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.nio.file.Path;

public final class AXStorageLayout {
    private final Path root;
    private volatile AXRagPathResolution ragPathResolution;

    public AXStorageLayout(ITianshuConfig config) {
        Path llmBasePath = config == null ? Path.of("config", "TianshuAIAX", "module", "llm") : config.getLlmBasePath();
        this.root = llmBasePath.resolve("cache").resolve("AX");
    }

    public void updateRagPathResolution(AXRagPathResolution resolution) {
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

    public Path worldRoot(AXScope scope) {
        String worldId = scope == null ? "unknown_world" : scope.worldId();
        return worldsRoot().resolve(safeName(worldId));
    }

    public Path memoryRagRoot(AXScope scope) {
        AXRagPathResolution resolution = ragPathResolution;
        if (resolution != null && resolution.valid()) {
            return resolution.memoryRagRoot();
        }
        return worldRoot(scope).resolve("memory_rag");
    }

    public Path memoryRagFile(AXScope scope) {
        AXRagPathResolution resolution = ragPathResolution;
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
