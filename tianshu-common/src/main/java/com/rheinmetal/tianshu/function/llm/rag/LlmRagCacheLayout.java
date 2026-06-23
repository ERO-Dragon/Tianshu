package com.rheinmetal.tianshu.function.llm.rag;

import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.scope.WorldScope;
import com.rheinmetal.tianshu.core.scope.WorldScopeProvider;

import java.nio.file.Path;

public final class LlmRagCacheLayout {
    private final ITianshuConfig config;
    private final WorldScopeProvider worldScopeProvider;

    public LlmRagCacheLayout(ITianshuConfig config, WorldScopeProvider worldScopeProvider) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.config = config;
        this.worldScopeProvider = worldScopeProvider;
    }

    public Path currentWorldCacheDirectory() {
        WorldScope scope = worldScopeProvider == null ? WorldScope.unknown() : worldScopeProvider.currentScope();
        return config.getLlmRagCacheRootPath().resolve(safeSegment(scope.worldId(), "unknown_world"));
    }

    public String cacheNamespace() {
        return stableSegment(config.getCustomLlmName()) + ":" + stableSegment(config.getLlmEmbeddingModelName());
    }

    private static String stableSegment(String value) {
        String normalized = value == null || value.isBlank() ? "default" : value.trim();
        return Integer.toHexString(java.util.Objects.hash(normalized));
    }

    private static String safeSegment(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        return normalized.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
