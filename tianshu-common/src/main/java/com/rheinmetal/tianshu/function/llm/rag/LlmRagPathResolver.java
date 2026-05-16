package com.rheinmetal.tianshu.function.llm.rag;

import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.scope.WorldScope;
import com.rheinmetal.tianshu.core.scope.WorldScopeProvider;

import java.nio.file.Path;

public final class LlmRagPathResolver {
    private final Path ragRoot;
    private final WorldScopeProvider worldScopeProvider;

    public LlmRagPathResolver(ITianshuConfig config, WorldScopeProvider worldScopeProvider) {
        this.ragRoot = config == null ? Path.of("config", "TianshuAIAssistant", "module", "llm", "rag", "root") : config.getLlmRagRootPath();
        this.worldScopeProvider = worldScopeProvider;
    }

    public LlmRagPathResolution resolveCurrent(String moduleId, String agentId) {
        WorldScope scope = worldScopeProvider == null ? WorldScope.unknown() : worldScopeProvider.currentScope();
        return resolve(scope.worldId(), moduleId, agentId);
    }

    private LlmRagPathResolution resolve(String worldId, String moduleId, String agentId) {
        String safeWorld = safeSegment(worldId, "unknown_world");
        String safeModule = safeSegment(moduleId, "unknown_module");
        String safeAgent = safeSegment(agentId, "default_agent");
        Path worldRoot = ragRoot.resolve(safeWorld);
        Path moduleRoot = worldRoot.resolve(safeModule);
        Path agentRoot = moduleRoot.resolve("agents").resolve(safeAgent);
        Path memoryRoot = agentRoot.resolve("memory_rag");
        return new LlmRagPathResolution(
                safeWorld,
                safeModule,
                safeAgent,
                safeModule + "/" + safeAgent,
                ragRoot,
                worldRoot,
                worldRoot.resolve("profiles.json"),
                moduleRoot,
                moduleRoot.resolve("static_rag"),
                agentRoot,
                memoryRoot,
                memoryRoot.resolve("memories.jsonl")
        );
    }

    private static String safeSegment(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        return normalized.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
