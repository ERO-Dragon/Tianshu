package com.rheinmetal.tianshu.function.weaving.account;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds the newest {@link LlmAccountSnapshot} per {@link LlmAccountKey} and dispatches the queries
 * that produce them.
 *
 * <p>Reads are lock-free and allocation-free apart from the returned immutable snapshot, so the HUD
 * can sample this from its refresh tick and from the render path without touching the network.
 * Writes arrive from the probe's threads and only ever replace a whole snapshot reference, which is
 * why a partially written answer cannot be observed.
 *
 * <p>Nothing here fabricates a number: until a probe is bound every key reads back as
 * {@link LlmAccountQueryState#UNWIRED}, and {@link #refresh} does nothing in that state so the HUD
 * placeholder stays stable instead of flickering through "querying".
 */
public final class LlmAccountQuery {
    /**
     * Minimum spacing between two queries for one key. It exists to keep a per-tick refresh call
     * from turning into a per-tick request; the provider's own interval and backoff settings
     * belong to the probe implementation (roadmap key {@code llm.balance.refreshIntervalSec}).
     */
    private static final long DEFAULT_MINIMUM_REFRESH_INTERVAL_MILLIS = 30_000L;

    private final Map<LlmAccountKey, QuerySlot> slots = new ConcurrentHashMap<>();
    private final long minimumRefreshIntervalMillis;
    private volatile LlmAccountProbe probe;

    public LlmAccountQuery() {
        this(LlmAccountProbe.NOT_WIRED, DEFAULT_MINIMUM_REFRESH_INTERVAL_MILLIS);
    }

    public LlmAccountQuery(LlmAccountProbe probe) {
        this(probe, DEFAULT_MINIMUM_REFRESH_INTERVAL_MILLIS);
    }

    public LlmAccountQuery(LlmAccountProbe probe, long minimumRefreshIntervalMillis) {
        this.probe = probe == null ? LlmAccountProbe.NOT_WIRED : probe;
        this.minimumRefreshIntervalMillis = Math.max(0L, minimumRefreshIntervalMillis);
    }

    /** Binds the provider implementation; a null probe restores the unwired placeholder. */
    public void bindProbe(LlmAccountProbe probe) {
        this.probe = probe == null ? LlmAccountProbe.NOT_WIRED : probe;
    }

    /** Whether a provider implementation is bound; when false no query is dispatched at all. */
    public boolean wired() {
        return probe.wired();
    }

    /** Newest known answer for the key; never null, and {@code UNWIRED} until an answer arrives. */
    public LlmAccountSnapshot snapshot(LlmAccountKey key) {
        LlmAccountKey effective = key == null ? LlmAccountKey.unspecified() : key;
        QuerySlot slot = slots.get(effective);
        LlmAccountSnapshot current = slot == null ? null : slot.snapshot;
        return current == null ? LlmAccountSnapshot.unwired(effective) : current;
    }

    /**
     * Asks the provider for fresh figures unless a query is already in flight or the minimum
     * spacing has not elapsed. Must stay non-blocking: the probe owns the threading.
     */
    public void refresh(LlmAccountKey key) {
        refresh(key, null);
    }

    /**
     * Same as {@link #refresh(LlmAccountKey)}, and runs {@code onAnswer} once the answer is stored.
     *
     * <p>It exists so a display can repaint when the figures arrive instead of polling them: the callback
     * runs on the probe's thread, before this method's caller ever sees the new snapshot otherwise. A
     * suppressed query (unconfigured key, unwired probe, in-flight request, too soon) answers nothing,
     * because nothing was asked.
     *
     * @param onAnswer may be {@code null}; it must not block and must marshal to its own thread
     */
    public void refresh(LlmAccountKey key, Runnable onAnswer) {
        LlmAccountKey effective = key == null ? LlmAccountKey.unspecified() : key;
        if (!effective.configured()) {
            return;
        }
        LlmAccountProbe currentProbe = probe;
        if (!currentProbe.wired()) {
            return;
        }
        QuerySlot slot = slots.computeIfAbsent(effective, ignored -> new QuerySlot());
        if (!slot.claim(System.currentTimeMillis(), minimumRefreshIntervalMillis)) {
            return;
        }
        slot.snapshot = LlmAccountSnapshot.querying(effective);
        try {
            currentProbe.query(effective, result -> {
                slot.complete(result == null
                        ? LlmAccountSnapshot.failed(effective, "", System.currentTimeMillis())
                        : result);
                if (onAnswer != null) {
                    onAnswer.run();
                }
            });
        } catch (RuntimeException dispatchFailure) {
            slot.complete(LlmAccountSnapshot.failed(effective, "", System.currentTimeMillis()));
            if (onAnswer != null) {
                onAnswer.run();
            }
        }
    }

    /** One key's mutable query slot; fields are written from the probe thread and read by the HUD. */
    private static final class QuerySlot {
        private final AtomicBoolean inFlight = new AtomicBoolean(false);
        private volatile LlmAccountSnapshot snapshot;
        private volatile long lastRequestMillis;

        private boolean claim(long nowMillis, long minimumIntervalMillis) {
            long last = lastRequestMillis;
            if (last != 0L && minimumIntervalMillis > 0L && nowMillis - last < minimumIntervalMillis) {
                return false;
            }
            if (!inFlight.compareAndSet(false, true)) {
                return false;
            }
            lastRequestMillis = nowMillis;
            return true;
        }

        private void complete(LlmAccountSnapshot result) {
            snapshot = result;
            inFlight.set(false);
        }
    }
}
