package com.rheinmetal.tianshu.function.ia.session;

import com.rheinmetal.tianshu.function.ia.context.DialogueContextFrame;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class DialogueContextFreezeStore {
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

    private record FrozenContext(DialogueContextFrame frame, long frozenAtMillis, long endedAtMillis) {
        FrozenContext endAt(long nowMillis) {
            return endedAtMillis > 0L ? this : new FrozenContext(frame, frozenAtMillis, nowMillis);
        }

        boolean expiredAt(long nowMillis, long retentionMillis) {
            long base = endedAtMillis > 0L ? endedAtMillis : frozenAtMillis;
            return base > 0L && nowMillis - base > retentionMillis;
        }
    }
}
