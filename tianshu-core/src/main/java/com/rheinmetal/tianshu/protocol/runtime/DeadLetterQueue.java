package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.protocol.DeadLetterPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

public final class DeadLetterQueue {
    private final Deque<DeadLetterRecord> records = new ConcurrentLinkedDeque<>();
    /** {@code ConcurrentLinkedDeque.size()} walks the list, and it used to run on every add. */
    private final AtomicInteger size = new AtomicInteger();
    private final int capacity;
    private final EnvelopeLifecycleStore lifecycleStore;
    private final LogSink logs;

    public DeadLetterQueue(int capacity, EnvelopeLifecycleStore lifecycleStore) {
        this(capacity, lifecycleStore, LogSink.SYSTEM);
    }

    public DeadLetterQueue(int capacity, EnvelopeLifecycleStore lifecycleStore, LogSink logSink) {
        this.capacity = Math.max(32, capacity);
        this.lifecycleStore = lifecycleStore;
        this.logs = logSink == null ? LogSink.SYSTEM : logSink;
    }

    public void add(TianshuEnvelope envelope, String errorCode, String reason, DeadLetterPolicy policy) {
        records.addFirst(DeadLetterRecord.from(envelope, errorCode, reason, policy));
        size.incrementAndGet();
        lifecycleStore.transition(envelope.envelopeId(), EnvelopeStatus.DEAD_LETTERED, errorCode, reason);
        // A dropped envelope is the one case where "why" is unrecoverable afterwards: the payload is gone with the
        // record once the queue rotates, so the reason has to leave the queue at the same time it enters it.
        logs.warn("dead letter: id=" + envelope.envelopeId()
                + ", trace=" + envelope.traceId()
                + ", code=" + errorCode
                + (reason == null || reason.isBlank() ? "" : ", reason=" + reason));
        while (size.get() > capacity) {
            if (records.pollLast() == null) {
                size.set(0);
                break;
            }
            size.decrementAndGet();
        }
    }

    public List<DeadLetterRecord> snapshot(int limit) {
        List<DeadLetterRecord> result = new ArrayList<>();
        int count = 0;
        for (DeadLetterRecord record : records) {
            result.add(record);
            count++;
            if (count >= limit) break;
        }
        return result;
    }
}
