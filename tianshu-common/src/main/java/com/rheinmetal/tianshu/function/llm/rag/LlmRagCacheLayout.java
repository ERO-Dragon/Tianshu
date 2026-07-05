package com.rheinmetal.tianshu.function.llm.rag;

import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.scope.WorldScopeProvider;

import java.nio.file.Path;

public final class LlmRagCacheLayout {
    private final ITianshuConfig config;

    public LlmRagCacheLayout(ITianshuConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.config = config;
    }

    public LlmRagCacheLayout(ITianshuConfig config, WorldScopeProvider ignoredWorldScopeProvider) {
        this(config);
    }

    public Path cacheDirectory() {
        return config.getLlmRagCacheRootPath().resolve("entries");
    }

    public String cacheNamespace() {
        return stableSegment(config.getCustomLlmName()) + ":" + stableSegment(config.getLlmEmbeddingModelName());
    }

    private static String stableSegment(String value) {
        String normalized = value == null || value.isBlank() ? "default" : value.trim();
        return Integer.toHexString(java.util.Objects.hash(normalized));
    }

}
