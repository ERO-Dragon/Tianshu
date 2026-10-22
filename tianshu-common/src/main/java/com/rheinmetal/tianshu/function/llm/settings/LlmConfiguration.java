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

    /** Provider of the entry synthesised from the legacy single-model keys. */
    String DEFAULT_LLM_PROVIDER_ID = "deepseek";

    /**
     * Declared context window of the cloud model, in tokens. The cloud endpoint does not report its
     * window, so it has to be configured; every AX budget value is derived from this single number.
     * Defaults to the 100K tier.
     */
    int DEFAULT_LLM_CONTEXT_WINDOW_TOKENS = 102_400;

    /**
     * Upper bound of a declared context window; the storage layer clamps to it.
     *
     * <p>There is no lower bound here on purpose: the smallest accepted window is the smallest budget
     * tier, and {@link LlmContextTiers#minimumTier()} is the only definition of it. A second constant
     * would be a second answer, and the two would drift apart the moment a tier is added.
     */
    int MAX_LLM_CONTEXT_WINDOW_TOKENS = 2_097_152;

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
     *
     * These prices feed the local estimate only. Balance and cost displayed as authoritative come
     * from the provider's own account answer (see {@code function.llm.account}); this arithmetic
     * exists for reconciliation and is labelled as an estimate in the HUD. It must never be
     * presented as a bill, and a provider figure must never be corrected with it, because a balance
     * difference also carries grants and refunds that token pricing cannot see.
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

    /**
     * Base URL of the <em>active</em> model. This is the legacy single-model accessor: the chat path,
     * the settings page and the account key all keep reading it, so an implementation that stores a
     * model list has to report the active entry's value here. The default reports the single model
     * this profile knows, which is by definition the active one.
     */
    default String getLlmRemoteBaseUrl() {
        return DEFAULT_LLM_REMOTE_BASE_URL;
    }

    /** Bearer credential of the active model. Never log, echo or serialise this value. */
    default String getLlmRemoteApiKey() {
        return "";
    }

    /** Provider model id of the active model. Same contract as {@link #getLlmRemoteBaseUrl()}. */
    default String getLlmRemoteModel() {
        return DEFAULT_LLM_REMOTE_MODEL;
    }

    /**
     * Usable context window of the active model; see {@link #DEFAULT_LLM_CONTEXT_WINDOW_TOKENS}.
     * Same contract as {@link #getLlmRemoteBaseUrl()}.
     */
    default int getLlmContextWindowTokens() {
        return DEFAULT_LLM_CONTEXT_WINDOW_TOKENS;
    }

    /*
     * Multi-model storage.
     *
     * The four getters above stay authoritative for the *active* model and every runtime caller
     * keeps using them, so nothing on the chat path has to change. Implementations that grow a real
     * model list must keep that invariant: whenever the active entry changes, getLlmRemoteBaseUrl /
     * getLlmRemoteApiKey / getLlmRemoteModel / getLlmContextWindowTokens must report that entry.
     *
     * This direction of the dependency is deliberate. The list below is the thing an old config file
     * does not have, so its default has to be synthesised from the single-model getters; making those
     * getters resolve through the list instead would close the loop and never terminate.
     */

    /**
     * Every configured model, in configuration order and never empty.
     *
     * <p>The default is the single legacy model, synthesised from the getters above, which is exactly
     * what an old config file holds — so the migration is a read, and reading it twice yields the
     * same answer.
     */
    default java.util.List<LlmModelEntry> getLlmModels() {
        return java.util.List.of(LlmModelEntry.legacy(
                getLlmRemoteBaseUrl(),
                getLlmRemoteModel(),
                getLlmContextWindowTokens()
        ));
    }

    /** Id of the entry the runtime currently uses; always the id of one of {@link #getLlmModels()}. */
    default String getLlmActiveModelId() {
        return LlmModelEntry.LEGACY_ID;
    }

    /**
     * Bearer credential of one entry. Kept out of {@link LlmModelEntry} so that serialising or
     * logging an entry can never leak it; the storage layer keys it by entry id instead.
     *
     * <p>The default answers the active entry with the single legacy credential and every other id
     * with nothing, because a profile without a model list only has that one credential.
     */
    default String getLlmApiKey(String modelId) {
        String activeModelId = getLlmActiveModelId();
        return activeModelId != null && activeModelId.equals(modelId) ? getLlmRemoteApiKey() : "";
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
