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
 * <p>One entry per speech session, consumed by the arbitration that resolves that turn. Entries expire on
 * their own retention and the store is capped, so a turn that never reaches arbitration — a failed
 * recognition, an expired request, a round with no participant — cannot turn into unbounded memory.</p>
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
        contexts.put(sourceSessionId, new FrozenContext(frame, Math.max(0L, nowMillis), 0L));
        if (contexts.size() > MAX_CONTEXTS) {
            evictOldest();
        }
    }

    public void markEnded(long sourceSessionId, long nowMillis) {
        if (sourceSessionId <= 0L) {
            return;
        }
        contexts.computeIfPresent(sourceSessionId, (ignored, existing) -> existing.endAt(Math.max(0L, nowMillis)));
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
                    .min(Comparator.comparingLong(entry -> entry.getValue().baseMillis()))
                    .map(Map.Entry::getKey);
            if (oldest.isEmpty() || contexts.remove(oldest.get()) == null) {
                return;
            }
        }
    }

    private record FrozenContext(DialogueContextFrame frame, long frozenAtMillis, long endedAtMillis) {
        FrozenContext endAt(long nowMillis) {
            return endedAtMillis > 0L ? this : new FrozenContext(frame, frozenAtMillis, nowMillis);
        }

        long baseMillis() {
            return endedAtMillis > 0L ? endedAtMillis : frozenAtMillis;
        }

        boolean expiredAt(long nowMillis, long retentionMillis) {
            long base = baseMillis();
            return base > 0L && nowMillis - base > retentionMillis;
        }
    }
}