package com.rheinmetal.tianshu.function.weaving.service;

/**
 * How the local embedding engine's load ended.
 *
 * <p>One engine reports exactly once, on whichever thread resolved the load. The three cases are the
 * whole CPU/GPU policy: the requested position worked, the requested position failed and the CPU
 * carried the lane, or nothing could embed and the lane stays off until the engine is rebuilt.
 *
 * @param outcome what the lane can do from now on
 * @param detail  why the requested position was abandoned, or why nothing could embed; empty when
 *                there was nothing to report
 */
public record EmbeddingLoadReport(Outcome outcome, String detail) {
    /** The closed set of ways a load can end. */
    public enum Outcome {
        /** The requested position loaded the model and produced a vector. */
        READY,
        /** The requested position failed, the CPU produced a vector: the lane works, slower. */
        DEGRADED,
        /** Neither position could embed: the model is unusable for this session. */
        UNAVAILABLE
    }

    public EmbeddingLoadReport {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome");
        }
        detail = detail == null ? "" : detail.trim();
    }

    public static EmbeddingLoadReport ready() {
        return new EmbeddingLoadReport(Outcome.READY, "");
    }

    public static EmbeddingLoadReport degraded(String detail) {
        return new EmbeddingLoadReport(Outcome.DEGRADED, detail);
    }

    public static EmbeddingLoadReport unavailable(String detail) {
        return new EmbeddingLoadReport(Outcome.UNAVAILABLE, detail);
    }

    /** Whether this load leaves an engine that can produce vectors. */
    public boolean usable() {
        return outcome != Outcome.UNAVAILABLE;
    }
}
