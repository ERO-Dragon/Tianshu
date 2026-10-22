package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.DeadLetterPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class DeadLetterQueue {
    private final Deque<DeadLetterRecord> records = new ConcurrentLinkedDeque<>();
    private final int capacity;
    private final EnvelopeLifecycleStore lifecycleStore;

    public DeadLetterQueue(int capacity, EnvelopeLifecycleStore lifecycleStore) {
        this.capacity = Math.max(32, capacity);
        this.lifecycleStore = lifecycleStore;
    }

    public void add(TianshuEnvelope envelope, String errorCode, String reason, DeadLetterPolicy policy) {
        records.addFirst(DeadLetterRecord.from(envelope, errorCode, reason, policy));
        lifecycleStore.transition(envelope.envelopeId(), EnvelopeStatus.DEAD_LETTERED, errorCode, reason);
        while (records.size() > capacity) {
            records.pollLast();
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
