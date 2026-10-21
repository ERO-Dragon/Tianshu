package com.rheinmetal.tianshu.function.llm.usage;

/**
 * How one request split its prompt between the provider's prefix cache and a fresh read.
 *
 * <p>Only the provider knows this split: the token counters that travel with a result carry prompt,
 * completion and output sizes, but never how much of the prompt was served from cache. The inference
 * client therefore reports it through a per-request hook, and this record is what crosses that seam.
 *
 * <p>Both values are {@code -1} when the provider did not report a split at all, which is not the
 * same as a reported {@code 0}: "not cached" is a measurement, "not reported" is the absence of one.
 */
public record LlmPromptCacheUsage(int cacheHitTokens, int cacheMissTokens) {
    private static final int NOT_REPORTED = -1;
    private static final LlmPromptCacheUsage UNKNOWN = new LlmPromptCacheUsage(NOT_REPORTED, NOT_REPORTED);

    public LlmPromptCacheUsage {
        cacheHitTokens = Math.max(NOT_REPORTED, cacheHitTokens);
        cacheMissTokens = Math.max(NOT_REPORTED, cacheMissTokens);
    }

    /** No split was reported for this request. */
    public static LlmPromptCacheUsage unknown() {
        return UNKNOWN;
    }

    /** Whether the provider reported a split; when false neither count may be read as a measurement. */
    public boolean known() {
        return cacheHitTokens >= 0 && cacheMissTokens >= 0;
    }
}
