package com.rheinmetal.tianshu.protocol.broker;

import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.registry.HandlerRegistration;
import com.rheinmetal.tianshu.protocol.runtime.MainThreadExecutor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * Delivers to the platform's own thread.
 *
 * <p>The queue here is bounded like every other broker's. It used to be unbounded, and the platform thread is the one
 * resource that cannot be scaled: a producer that outran the tick piled envelopes up with nothing to stop it. When the
 * queue is full, envelopes below {@link Priority#HIGH} are refused — the same floor the parallel broker uses — because
 * the traffic that has to survive a burst is the traffic that ends it.</p>
 */
public final class MainThreadBroker implements ProtocolBroker {
    private final String brokerId;
    private final int queueCapacity;
    private final MainThreadExecutor mainThreadExecutor;
    private final Map<String, TianshuEnvelope> pending = new ConcurrentHashMap<>();
    private final Map<String, TianshuEnvelope> running = new ConcurrentHashMap<>();
    /** Guards the admit-or-reject decision; the execute call is made outside it. */
    private final Object admissionLock = new Object();

    public MainThreadBroker(String brokerId, MainThreadExecutor mainThreadExecutor, int queueCapacity) {
        this.brokerId = brokerId;
        this.mainThreadExecutor = mainThreadExecutor;
        this.queueCapacity = Math.max(1, queueCapacity);
    }

    @Override
    public BrokerSubmitResult submit(TianshuEnvelope envelope, HandlerRegistration registration, ProtocolRuntime runtime) {
        synchronized (admissionLock) {
            if (pending.size() >= queueCapacity && ProtocolBroker.belowFloor(Priority.HIGH, envelope)) {
                runtime.lifecycle().transition(envelope.envelopeId(), EnvelopeStatus.REJECTED,
                        "BROKER_QUEUE_FULL", "Main thread queue is full");
                return BrokerSubmitResult.rejected("BROKER_QUEUE_FULL", "Main thread queue is full");
            }
            pending.put(envelope.envelopeId(), envelope);
            runtime.lifecycle().transition(envelope.envelopeId(), EnvelopeStatus.QUEUED, "QUEUED_MAIN", brokerId);
        }
        try {
            mainThreadExecutor.execute(() -> deliver(envelope, registration, runtime));
        } catch (RejectedExecutionException rejected) {
            // The platform refused the task — a client shutting down, or a server tick loop already stopped. Leaving
            // the envelope in QUEUED would make it wait for a thread that will never run it.
            pending.remove(envelope.envelopeId());
            runtime.lifecycle().transition(envelope.envelopeId(), EnvelopeStatus.REJECTED,
                    "MAIN_THREAD_REJECTED", "The main thread refused the task");
            return BrokerSubmitResult.rejected("MAIN_THREAD_REJECTED", "The main thread refused the task");
        }
        return BrokerSubmitResult.accept();
    }

    private void deliver(TianshuEnvelope envelope, HandlerRegistration registration, ProtocolRuntime runtime) {
        if (pending.remove(envelope.envelopeId()) == null || runtime.cancellation().isCancelled(envelope.envelopeId())) {
            return;
        }
        running.put(envelope.envelopeId(), envelope);
        try {
            if (runtime.cancellation().isCancelled(envelope.envelopeId()) || runtime.expireIfDue(envelope)) {
                return;
            }
            runtime.lifecycle().transition(envelope.envelopeId(), EnvelopeStatus.RUNNING, "RUNNING_MAIN", brokerId);
            registration.handler().handle(envelope, runtime.context());
            if (registration.capabilityDescriptor().completionPolicy() == CompletionPolicy.AUTO_COMPLETE_ON_RETURN
                    && !runtime.cancellation().isCancelled(envelope.envelopeId())) {
                runtime.lifecycle().transition(envelope.envelopeId(), EnvelopeStatus.COMPLETED, "COMPLETED", brokerId);
            }
        } catch (Exception exception) {
            runtime.handleFailure(envelope, "MAIN_THREAD_HANDLER_EXCEPTION", exception.getMessage(), exception);
        } finally {
            running.remove(envelope.envelopeId());
        }
    }

    @Override
    public BrokerSnapshot snapshot() {
        ArrayList<String> envelopeIds = new ArrayList<>(pending.keySet());
        envelopeIds.addAll(running.keySet());
        return new BrokerSnapshot(brokerId, pending.size(), running.size(), envelopeIds);
    }

    @Override
    public void cancel(String envelopeId, String reasonCode, String message) {
        // A queued envelope is cancelled by taking it out of the queue. One already running on the main thread cannot be
        // interrupted there, so there is nothing else to do.
        pending.remove(envelopeId);
    }

    @Override
    public String brokerId() {
        return brokerId;
    }
}