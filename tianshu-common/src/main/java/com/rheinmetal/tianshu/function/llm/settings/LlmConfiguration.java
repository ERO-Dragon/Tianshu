package com.rheinmetal.tianshu.function.llm.settings;

import java.nio.file.Path;

public interface LlmConfiguration {
    /** Default cloud endpoint; the remote lane appends {@code /chat/completions} to it. */
    String DEFAULT_LLM_REMOTE_BASE_URL = "https://api.deepseek.com/v1";
    String DEFAULT_LLM_REMOTE_MODEL = "deepseek-chat";

    /**
     * Declared context window of the cloud model, in tokens. The cloud endpoint does not report its
     * window, so it has to be configured; every AX budget value is derived from this single number.
     * Defaults to the 100K tier.
     */
    int DEFAULT_LLM_CONTEXT_WINDOW_TOKENS = 102_400;

    boolean isLlmEnabled();

    String getCustomLlmName();

    Path getLlmBasePath();

    /*
     * Cloud is the only chat lane. The local GGUF engine is sealed: it no longer serves chat, it only
     * provides embeddings for vector RAG. There is deliberately no switch back to local chat.
     */

    default String getLlmRemoteBaseUrl() {
        return DEFAULT_LLM_REMOTE_BASE_URL;
    }

    /** Bearer credential of the cloud endpoint. Never log, echo or serialise this value. */
    default String getLlmRemoteApiKey() {
        return "";
    }

    default String getLlmRemoteModel() {
        return DEFAULT_LLM_REMOTE_MODEL;
    }

    /** Usable context window of the active cloud model; see {@link #DEFAULT_LLM_CONTEXT_WINDOW_TOKENS}. */
    default int getLlmContextWindowTokens() {
        return DEFAULT_LLM_CONTEXT_WINDOW_TOKENS;
    }

    /**
     * Selected context budget tier. The window above only says which tiers physically fit; this is
     * the one the player actually chose, and every budget value derives from it.
     */
    default int getLlmContextTier() {
        return LlmContextTiers.DEFAULT_TIER;
    }

    default Path getLlmMtpDraftGgufFilePath() {
        return null;
    }

    default String getLlmEmbeddingModelName() {
        return "";
    }

    default String getLlmGpuDeviceId() {
        return "";
    }

    default boolean isLlmFrameGuardEnabled() {
        return true;
    }

    default int getLlmFrameGuardTargetFps() {
        return 60;
    }

    default boolean isLlmMtpEnabled() {
        return false;
    }

    default int getLlmRequestTimeoutSeconds() {
        return 120;
    }

    /** Internal world-session delay before automatic model loading starts. */
    default long getLlmAutoLoadDelayMillis() {
        return 3_000L;
    }

    default int getLlmTaskAdmissionQueueSize() {
        return 2;
    }

    default int getLlmTaskAgingBoostPerRequest() {
        return 1;
    }

    default int getLlmLibsChatQueueSize() {
        return 1;
    }

    default String getLlmCacheTypeK() {
        return "q8_0";
    }

    default String getLlmCacheTypeV() {
        return "q8_0";
    }

    default Path getLlmRagRootPath() {
        return getLlmBasePath().resolve("rag").resolve("root");
    }

    default Path getLlmRagCacheRootPath() {
        return getLlmBasePath().resolve("ragCache");
    }
}
