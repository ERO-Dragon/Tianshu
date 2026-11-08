package com.rheinmetal.tianshu.function.ia.session;

import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextFrame;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The interaction frame frozen when one speech turn started.
 *
 * <p>One entry per speech session, consumed by the arbitration that resolves that turn. A turn that ends
 * without text is abandoned explicitly; one that never reaches arbitration anyway expires on its own
 * retention, and the store is capped, so neither can grow without bound.</p>
 */
public final class DialogueContextFreezeStore {
    /** Upper bound of retained frames; a speech turn is consumed within seconds, so this is generous. */
    private static final int MAX_CONTEXTS = 64;

    private final ConcurrentMap<Long, FrozenContext> contexts = new ConcurrentHashMap<>();
    private final long retentionMillis;

    public DialogueContextFreezeStore(Duration retention) {
        Duration effectiveRetention = retention == null || retention.isNegative() || retention.isZero()
                ? Duration.ofSeconds(30)
                : retention;
        this.retentionMillis = effectiveRetention.toMillis();
    }

    public void freeze(long sourceSessionId, DialogueContextFrame frame, long nowMillis) {
        if (sourceSessionId <= 0L || frame == null) {
            return;
        }
        contexts.put(sourceSessionId, new FrozenContext(frame, Math.max(0L, nowMillis)));
        if (contexts.size() > MAX_CONTEXTS) {
            evictOldest();
        }
    }

    /**
     * Drops a turn's frame without arbitration.
     *
     * <p>A silent mark that never produced text (empty or failed recognition, cancellation, a stop gesture)
     * has no arbitration coming, so its frame must not keep pinning the marker until retention expires.</p>
     */
    public void abandon(long sourceSessionId) {
        if (sourceSessionId > 0L) {
            contexts.remove(sourceSessionId);
        }
    }

    /**
     * The frame a marker should be computed from right now: the frozen frame while a speech turn is still in
     * flight, otherwise the caller's live frame.
     *
     * <p>Newest first: a turn that never reaches arbitration can leave a frame behind until retention
     * expires, and the current turn is always the newest one.</p>
     */
    public Optional<DialogueContextFrame> active(long nowMillis) {
        return contexts.values().stream()
                .filter(context -> !context.expiredAt(nowMillis, retentionMillis))
                .max(Comparator.comparingLong(FrozenContext::frozenAtMillis))
                .map(FrozenContext::frame);
    }

    /**
     * Consumes the newest in-flight frame, whatever its session id.
     *
     * <p>The fallback for a turn whose speech session id and arbitration request id do not line up: the
     * single in-flight frame is still that turn's frame, and leaving it behind would pin the marker to a
     * frame no arbitration will ever consume.</p>
     */
    public Optional<DialogueContextFrame> consumeNewest(long nowMillis) {
        Optional<Long> newest = contexts.entrySet().stream()
                .filter(entry -> !entry.getValue().expiredAt(nowMillis, retentionMillis))
                .max(Comparator.comparingLong(entry -> entry.getValue().frozenAtMillis()))
                .map(Map.Entry::getKey);
        return newest.flatMap(sessionId -> consume(sessionId, nowMillis));
    }

    public Optional<DialogueContextFrame> consume(long sourceSessionId, long nowMillis) {
        if (sourceSessionId <= 0L) {
            return Optional.empty();
        }
        FrozenContext context = contexts.get(sourceSessionId);
        if (context == null) {
            return Optional.empty();
        }
        if (context.expiredAt(nowMillis, retentionMillis)) {
            contexts.remove(sourceSessionId, context);
            return Optional.empty();
        }
        contexts.remove(sourceSessionId, context);
        return Optional.of(context.frame());
    }

    public void sweep(long nowMillis) {
        contexts.entrySet().removeIf(entry -> entry.getValue().expiredAt(nowMillis, retentionMillis));
    }

    public void clear() {
        contexts.clear();
    }

    private void evictOldest() {
        while (contexts.size() > MAX_CONTEXTS) {
            Optional<Long> oldest = contexts.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().frozenAtMillis()))
                    .map(Map.Entry::getKey);
            if (oldest.isEmpty() || contexts.remove(oldest.get()) == null) {
                return;
            }
        }
    }

    private record FrozenContext(DialogueContextFrame frame, long frozenAtMillis) {
        boolean expiredAt(long nowMillis, long retentionMillis) {
            return frozenAtMillis > 0L && nowMillis - frozenAtMillis > retentionMillis;
        }
    }}