package com.rheinmetal.tianshu.protocol.broker;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.registry.HandlerRegistration;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One queued broker delivery.
 *
 * <p>{@code sequence} is the source of the dispatch-order contract: it is allocated when the task is built —
 * that is, in enqueue order — so a task always carries a strictly larger sequence than every task enqueued
 * before it. {@code createdAt} cannot carry that contract on its own: it is a millisecond timestamp, so
 * envelopes created within the same millisecond compare equal on it, and a
 * {@link java.util.PriorityBlockingQueue} is then free to hand them back in heap order instead of in enqueue
 * order. That would leave dispatch order to a placement detail nobody controls.</p>
 *
 * <p>{@link #compareTo} therefore orders by priority first, then by sequence, and only falls back to
 * {@code createdAt} when two tasks carry the same sequence — possible only through the canonical constructor,
 * which allocation sites must not call directly.</p>
 */
public record BrokerTask(
        TianshuEnvelope envelope,
        HandlerRegistration registration,
        ProtocolRuntime runtime,
        long sequence
) implements Comparable<BrokerTask> {

    private static final AtomicLong NEXT_SEQUENCE = new AtomicLong();

    /** Allocates the sequence in construction order; brokers always build their tasks through this constructor. */
    public BrokerTask(TianshuEnvelope envelope, HandlerRegistration registration, ProtocolRuntime runtime) {
        this(envelope, registration, runtime, NEXT_SEQUENCE.incrementAndGet());
    }

    @Override
    public int compareTo(BrokerTask other) {
        int priorityCompare = Integer.compare(other.envelope.header().priority().weight(), envelope.header().priority().weight());
        if (priorityCompare != 0) {
            return priorityCompare;
        }
        int sequenceCompare = Long.compare(sequence, other.sequence);
        if (sequenceCompare != 0) {
            return sequenceCompare;
        }
        return Long.compare(envelope.header().createdAt(), other.envelope.header().createdAt());
    }
}
