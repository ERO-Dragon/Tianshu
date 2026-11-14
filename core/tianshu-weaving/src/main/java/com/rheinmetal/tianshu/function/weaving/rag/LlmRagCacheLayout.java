package com.rheinmetal.tianshu.function.weaving.rag;

import com.rheinmetal.tianshu.function.weaving.settings.WeavingConfiguration;
import com.rheinmetal.tianshu.core.scope.WorldScopeProvider;

import java.nio.file.Path;

public final class LlmRagCacheLayout {
    private final WeavingConfiguration config;

    public LlmRagCacheLayout(WeavingConfiguration config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.config = config;
    }

    public LlmRagCacheLayout(WeavingConfiguration config, WorldScopeProvider ignoredWorldScopeProvider) {
        this(config);
    }

    public Path cacheDirectory() {
        return config.getLlmRagCacheRootPath().resolve("entries");
    }

    /**
     * The namespace every stored vector belongs to.
     *
     * <p>It is keyed by the embedding model that will actually produce the vectors, not by the raw string in
     * the config: vectors from two models are not comparable, so a stored entry is only reusable inside the
     * namespace of the model that wrote it. Resolving the name first also means a config that still names a
     * retired model cannot keep reusing the retired model's vectors — it lands in the new model's
     * namespace, where nothing matches and the index is rebuilt.
     */
    public String cacheNamespace() {
        return stableSegment(config.getCustomLlmName()) + ":" + stableSegment(config.getLlmEmbeddingModelName());
    }

    private static String stableSegment(String value) {
        String normalized = value == null || value.isBlank() ? "default" : value.trim();
        return Integer.toHexString(java.util.Objects.hash(normalized));
    }

}
