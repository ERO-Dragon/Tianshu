package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class StormGuard {
    private final Map<String, CounterWindow> sourceCounters = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> rejectedByCode = new ConcurrentHashMap<>();
    private final int defaultLimitPerSecond;
    private final int maxTraceDepth;

    public StormGuard(int defaultLimitPerSecond, int maxTraceDepth) {
        this.defaultLimitPerSecond = Math.max(1, defaultLimitPerSecond);
        this.maxTraceDepth = Math.max(1, maxTraceDepth);
    }

    public GuardResult check(TianshuEnvelope envelope, int traceDepth, int specificLimitPerSecond) {
        int limit = specificLimitPerSecond > 0 ? specificLimitPerSecond : defaultLimitPerSecond;
        if (traceDepth > maxTraceDepth) {
            rejectedByCode.computeIfAbsent("TRACE_DEPTH_EXCEEDED", key -> new AtomicInteger()).incrementAndGet();
            return GuardResult.reject("TRACE_DEPTH_EXCEEDED", "Trace derivation depth exceeded");
        }
        String key = envelope.header().sourceId() + ":" + envelope.header().targetMode() + ":" + envelope.header().target();
        CounterWindow counter = sourceCounters.computeIfAbsent(key, ignored -> new CounterWindow());
        if (!counter.allow(limit)) {
            rejectedByCode.computeIfAbsent("SOURCE_RATE_LIMITED", ignored -> new AtomicInteger()).incrementAndGet();
            return GuardResult.reject("SOURCE_RATE_LIMITED", "Source rate limit exceeded");
        }
        return GuardResult.accept();
    }

    public Map<String, Integer> rejectionSnapshot() {
        Map<String, Integer> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, AtomicInteger> entry : rejectedByCode.entrySet()) {
            result.put(entry.getKey(), entry.getValue().get());
        }
        return result;
    }

    private static final class CounterWindow {
        private long second = System.currentTimeMillis() / 1000L;
        private int count;

        synchronized boolean allow(int limit) {
            long nowSecond = System.currentTimeMillis() / 1000L;
            if (nowSecond != second) {
                second = nowSecond;
                count = 0;
            }
            count++;
            return count <= limit;
        }
    }
}
