package com.rheinmetal.tianshu.function.weaving.settings;

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

    /** GPU layers for the embedding model: 999 offloads every layer, 0 keeps the lane on the CPU. */
    int EMBEDDING_GPU_LAYERS_ALL = 999;
    /** CPU position: no layer offloaded. The position a GPU load falls back to, not the default. */
    int EMBEDDING_GPU_LAYERS_CPU = 0;

    /**
     * Where the embedding model runs; this single number is the whole CPU/GPU choice.
     *
     * <p>The default is the whole model on the card. The embedding backend creates a Vulkan device
     * inside the game process, next to Minecraft's OpenGL context, and that coexistence has failed on
     * some NVIDIA drivers: observed as {@code vk::Device::createComputePipeline: ErrorUnknown} while the
     * device was created, and as {@code GL_OUT_OF_MEMORY} moments after world entry — which is exactly
     * when the embedding load runs. Such a driver fails while plenty of memory is still free, so the
     * position cannot be inferred from hardware headroom and has to be an explicit choice — but it is
     * also exactly why the automatic fallback matters: a GPU load that fails hands the lane to the CPU
     * for that session instead of failing it, so the default position can be the faster one without
     * leaving such a driver with no retrieval at all.
     *
     * <p>Read-only like every other member of this port: the settings page writes the position through
     * the settings-access port, which is the only place a write belongs.
     */
    default int getLlmEmbeddingGpuLayers() {
        return EMBEDDING_GPU_LAYERS_ALL;
    }

    /**
     * Smallest accepted runtime window of the embedding context.
     *
     * <p>The window is a native-memory budget, not a model property: llama.cpp sizes the KV cache from
     * the whole window, and the embedding path of the bundled binding raises the batch size to the
     * window as well, so the compute arena grows with it. The floor therefore has to stay big enough
     * for one long retrieval document, or the engine would truncate the very text it is asked to
     * vectorise.
     */
    int MIN_EMBEDDING_CONTEXT_SIZE = 256;

    /** Largest accepted runtime window; the model's own ceiling is applied on top of this one. */
    int MAX_EMBEDDING_CONTEXT_SIZE = 8192;

    /**
     * Default runtime window of the embedding context.
     *
     * <p>The retrieval inputs of this build are one event sentence (sixty characters at most) and a
     * summary of a few hundred, so the 8K window the model was trained for costs eight times the KV
     * cache for zero extra recall. The window is not free: llama.cpp sizes the KV cache in proportion
     * to it, and that cache is native memory no collector can reclaim. 512 tokens is what a machine
     * whose page file is already nearly exhausted can still pay, and it covers the event sentence plus
     * the summary this build stores. Going lower starts truncating summaries, which are the carrier of
     * long-term memory — a truncation that costs retrieval quality silently instead of loudly.
     */
    int DEFAULT_EMBEDDING_CONTEXT_SIZE = 1024;

    /**
     * Runtime window of the embedding context, in tokens; the model's declared ceiling is the upper
     * bound and is applied when the engine is built.
     */
    default int getLlmEmbeddingContextSize() {
        return DEFAULT_EMBEDDING_CONTEXT_SIZE;
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
