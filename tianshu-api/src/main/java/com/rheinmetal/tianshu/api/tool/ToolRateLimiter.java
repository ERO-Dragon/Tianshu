package com.rheinmetal.tianshu.api.tool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Throttles player-driven tool calls: the client-to-server channel is the one input a hostile player controls, so
 * it gets a token bucket per {@code (tool, player)}. The agent and other mods are trusted peers and are not throttled.
 *
 * <p>Reads get a generous bucket, writes a strict one. A rejection is a {@link ToolError#RATE_LIMITED} value, so the
 * caller and the model see "slow down" instead of a timeout.</p>
 */
final class ToolRateLimiter {

    static final double READ_CALLS_PER_SECOND = 10.0;
    static final double WRITE_CALLS_PER_SECOND = 2.0;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    boolean allow(ToolId id, ToolCategory category, ToolCaller caller) {
        if (caller.kind() != ToolCaller.Kind.PLAYER) {
            return true;
        }
        double rate = category == ToolCategory.SERVER_WRITE ? WRITE_CALLS_PER_SECOND : READ_CALLS_PER_SECOND;
        return buckets.computeIfAbsent(id + "|" + caller.id(), ignored -> new Bucket(rate)).take(rate);
    }

    void forget(ToolId id) {
        String prefix = id + "|";
        buckets.keySet().removeIf(key -> key.startsWith(prefix));
    }

    void clear() {
        buckets.clear();
    }

    void clearCaller(String callerId) {
        String suffix = "|" + callerId;
        buckets.keySet().removeIf(key -> key.endsWith(suffix));
    }

    private static final class Bucket {
        private double tokens;
        private long nanos;

        Bucket(double burst) {
            tokens = burst;
            nanos = System.nanoTime();
        }

        synchronized boolean take(double rate) {
            long now = System.nanoTime();
            tokens = Math.min(rate, tokens + (now - nanos) / 1_000_000_000.0 * rate);
            nanos = now;
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }
    }
}
