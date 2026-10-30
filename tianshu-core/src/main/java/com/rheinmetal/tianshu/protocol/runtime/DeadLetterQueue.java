package com.rheinmetal.tianshu.protocol.runtime;

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

    public DeadLetterQueue(int capacity, EnvelopeLifecycleStore lifecycleStore) {
        this.capacity = Math.max(32, capacity);
        this.lifecycleStore = lifecycleStore;
    }

    public void add(TianshuEnvelope envelope, String errorCode, String reason, DeadLetterPolicy policy) {
        records.addFirst(DeadLetterRecord.from(envelope, errorCode, reason, policy));
        size.incrementAndGet();
        lifecycleStore.transition(envelope.envelopeId(), EnvelopeStatus.DEAD_LETTERED, errorCode, reason);
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
