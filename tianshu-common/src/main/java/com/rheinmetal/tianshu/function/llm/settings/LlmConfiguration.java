package com.rheinmetal.tianshu.function.llm.settings;

import java.nio.file.Path;

public interface LlmConfiguration {
    /** Default cloud endpoint; the remote lane appends {@code /chat/completions} to it. */
    String DEFAULT_LLM_REMOTE_BASE_URL = "https://api.deepseek.com/v1";
    /**
     * Default cloud model. Has to be a model the provider's price list actually carries: an unknown
     * id fails the request outright, and a fresh install would hit that on its first turn.
     */
    String DEFAULT_LLM_REMOTE_MODEL = "deepseek-flash";

    /**
     * Declared context window of the cloud model, in tokens. The cloud endpoint does not report its
     * window, so it has to be configured; every AX budget value is derived from this single number.
     * Defaults to the 100K tier.
     */
    int DEFAULT_LLM_CONTEXT_WINDOW_TOKENS = 102_400;

    /*
     * Token prices in CNY, per million tokens, as published by the provider for the mainland-China
     * endpoint. All three numbers share this one denomination; no amount may mix currencies.
     *
     * Source: https://api-docs.deepseek.com/zh-cn/quick_start/pricing (the CNY table — the /en page
     * quotes the same list in USD), read as of 2026-10. The page quotes off-peak and peak rates
     * for the flash line; a single configured number cannot follow the clock, so the peak rate is the
     * default: it is the upper bound of what a request can cost, and off-peak is exactly half of it.
     * Cache hits and misses are priced separately because a cached prompt costs about 1/50 of a fresh
     * one (0.04 vs 2.00), and charging both alike would overstate the bill several dozen times over.
     *
     * These are defaults only. The provider may change list prices; the amount shown to the player is
     * whatever the configured numbers are denominated in, so changing the denomination is a config
     * edit, not a code edit.
     */
    double DEFAULT_LLM_USAGE_CACHE_MISS_INPUT_PRICE_PER_MILLION = 2.00D;
    double DEFAULT_LLM_USAGE_CACHE_HIT_INPUT_PRICE_PER_MILLION = 0.04D;
    double DEFAULT_LLM_USAGE_OUTPUT_PRICE_PER_MILLION = 8.00D;
    /** Accepted range for a configured price; zero means "do not show an amount". */
    double MIN_LLM_USAGE_PRICE_PER_MILLION = 0.0D;
    double MAX_LLM_USAGE_PRICE_PER_MILLION = 10_000.0D;

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

    default String getLlmEmbeddingModelName() {
        return "";
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

    default Path getLlmRagRootPath() {
        return getLlmBasePath().resolve("rag").resolve("root");
    }

    default Path getLlmRagCacheRootPath() {
        return getLlmBasePath().resolve("ragCache");
    }

    /**
     * Price of one million prompt tokens the provider did not serve from its prefix cache, in CNY.
     *
     * @see #DEFAULT_LLM_USAGE_CACHE_MISS_INPUT_PRICE_PER_MILLION
     */
    default double getLlmUsageCacheMissInputPricePerMillion() {
        return DEFAULT_LLM_USAGE_CACHE_MISS_INPUT_PRICE_PER_MILLION;
    }

    /**
     * Price of one million prompt tokens the provider served from its prefix cache, in CNY.
     *
     * @see #DEFAULT_LLM_USAGE_CACHE_HIT_INPUT_PRICE_PER_MILLION
     */
    default double getLlmUsageCacheHitInputPricePerMillion() {
        return DEFAULT_LLM_USAGE_CACHE_HIT_INPUT_PRICE_PER_MILLION;
    }

    /**
     * Price of one million generated tokens, in CNY.
     *
     * @see #DEFAULT_LLM_USAGE_OUTPUT_PRICE_PER_MILLION
     */
    default double getLlmUsageOutputPricePerMillion() {
        return DEFAULT_LLM_USAGE_OUTPUT_PRICE_PER_MILLION;
    }
}
