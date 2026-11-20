package com.rheinmetal.tianshu.api.network;

/**
 * The ceilings the platform puts on any outbound request.
 *
 * <p>Modules do not get to raise these: a search result the size of a novel, or a fetch that never returns, is a
 * platform problem, not a per-module choice. They are constants rather than configuration for the same reason — the
 * moment they are configurable, someone sets them to "unlimited" and the gate stops protecting anything.</p>
 */
public final class ExternalLimits {

    /** Largest body a single request may bring back; beyond this the response is truncated by the caller. */
    public static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    /** Longest a single request may take, counted from connect to last byte. */
    public static final long MAX_TIMEOUT_MILLIS = 15_000L;

    private ExternalLimits() {
    }
}