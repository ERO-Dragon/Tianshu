package com.rheinmetal.tianshu.protocol.broker;

import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.registry.HandlerRegistration;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutionPolicy;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractQueueBroker implements ProtocolBroker {
    protected final String brokerId;
    protected final int queueCapacity;
    protected final int maxConcurrency;
    protected final PriorityBlockingQueue<BrokerTask> queue = new PriorityBlockingQueue<>();
    protected final Map<String, BrokerTask> running = new ConcurrentHashMap<>();
    protected final ProtocolExecutorManager executorManager;
    protected final ProtocolExecutionPolicy executionPolicy;
    protected final AtomicBoolean draining = new AtomicBoolean(false);
    /**
     * The executor handle of each dispatch, so a cancel can reach the thread instead of only the status table.
     *
     * <p>The holder goes in before the task is submitted and is filled right after: a task that settles synchronously
     * — a rejection, or a body that finishes before {@code submit} returns — must not leave a stale entry behind, and
     * the cancel path must never read a half-published handle.</p>
     */
    private final Map<String, java.util.concurrent.atomic.AtomicReference<ProtocolTaskHandle>> handles =
            new ConcurrentHashMap<>();

    protected AbstractQueueBroker(String brokerId, int queueCapacity, int maxConcurrency, ProtocolExecutorManager executorManager, ProtocolExecutionPolicy executionPolicy) {
        this.brokerId = brokerId;
        this.queueCapacity = Math.max(1, queueCapacity);
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.executorManager = executorManager;
        this.executionPolicy = executionPolicy == null ? new ProtocolExecutionPolicy() : executionPolicy;
    }

    /** Guards the admit-or-reject decision; deliberately not the broker monitor, which the drain path also touches. */
    private final Object admissionLock = new Object();

    @Override
    public BrokerSubmitResult submit(TianshuEnvelope envelope, HandlerRegistration registration, ProtocolRuntime runtime) {
        synchronized (admissionLock) {
            // The size check and the insert have to be one step: two callers could both see room for one and both
            // insert, which made a "bounded" queue unbounded exactly when it was under load.
            if (queue.size() >= queueCapacity && shouldRejectWhenFull(envelope)) {
                runtime.lifecycle().transition(envelope.envelopeId(), EnvelopeStatus.REJECTED, "BROKER_QUEUE_FULL", "Broker queue is full");
                return BrokerSubmitResult.rejected("BROKER_QUEUE_FULL", "Broker queue is full");
            }
            enqueue(new BrokerTask(envelope, registration, runtime));
            runtime.lifecycle().transition(envelope.envelopeId(), EnvelopeStatus.QUEUED, "QUEUED", brokerId);
        }
        drain();
        return BrokerSubmitResult.accept();
    }

    protected boolean shouldRejectWhenFull(TianshuEnvelope envelope) {
        return true;
    }

    /**
     * Whether cancelling should also interrupt a task that is already running.
     *
     * <p>Off for the ordinary queue brokers and on only for {@link com.rheinmetal.tianshu.protocol.BrokerType#EXCLUSIVE_INTERRUPT}:
     * for most brokers a cancel means "this envelope will not run and its result is void", delivered through the
     * cancellation registry, and interrupting a handler in the middle of an I/O call has to be a deliberate per-broker
     * choice. Carrying the handle lets the broker that promises interruption actually deliver it.</p>
     */
    protected boolean interruptsRunningTasks() {
        return false;
    }

    protected void enqueue(BrokerTask task) {
        queue.offer(task);
    }

    protected void drain() {
        if (!draining.compareAndSet(false, true)) return;
        try {
            while (running.size() < maxConcurrency) {
                BrokerTask task = queue.poll();
                if (task == null) break;
                running.put(task.envelope().envelopeId(), task);
                task.runtime().lifecycle().transition(task.envelope().envelopeId(), EnvelopeStatus.DISPATCHED, "DISPATCHED", brokerId);
                java.util.concurrent.atomic.AtomicReference<ProtocolTaskHandle> holder =
                        new java.util.concurrent.atomic.AtomicReference<>();
                String envelopeId = task.envelope().envelopeId();
                handles.put(envelopeId, holder);
                ProtocolTaskHandle handle = executorManager.submit(taskSpec(task), () -> runTask(task),
                        () -> {
                            handles.remove(envelopeId, holder);
                            running.remove(envelopeId);
                            drain();
                        });
                holder.set(handle);
                if (handle.state() == ProtocolTaskState.REJECTED) {
                    // The submission never ran, so settlement already gave the slot back; the caller still has to be
                    // told why nothing happened.
                    task.runtime().handleFailure(
                            task.envelope(),
                            "BROKER_EXECUTOR_REJECTED",
                            "Executor rejected the dispatch: "
                                    + handle.failureCause().map(Throwable::getMessage).orElse(""),
                            handle.failureCause().orElse(null)
                    );
                }
            }
        } finally {
            draining.set(false);
            if (!queue.isEmpty() && running.size() < maxConcurrency) drain();
        }
    }

    private ProtocolTaskSpec taskSpec(BrokerTask task) {
        return ProtocolTaskSpec.builder()
            .moduleId(task.registration().capabilityDescriptor().capabilityId())
            .envelopeId(task.envelope().envelopeId())
            .lane(executionPolicy.resolveLane(task.envelope(), task.registration()))
            .priority(task.envelope().header().priority())
            .maxConcurrency(maxConcurrency)
            .queueCapacity(queueCapacity)
            .concurrencyKey(brokerId)
            .build();
    }

    protected void runTask(BrokerTask task) {
        TianshuEnvelope envelope = task.envelope();
        ProtocolRuntime runtime = task.runtime();
        try {
            if (runtime.cancellation().isCancelled(envelope.envelopeId())) return;
            if (runtime.expireIfDue(envelope)) {
                // The submit-time check was the only one, so an envelope that waited five minutes behind a full queue
                // ran anyway and ENVELOPE_EXPIRED was never recorded anywhere.
                return;
            }
            runtime.lifecycle().transition(envelope.envelopeId(), EnvelopeStatus.RUNNING, "RUNNING", brokerId);
            task.registration().handler().handle(envelope, runtime.context());
            if (task.registration().capabilityDescriptor().completionPolicy() == CompletionPolicy.AUTO_COMPLETE_ON_RETURN && !runtime.cancellation().isCancelled(envelope.envelopeId())) {
                runtime.lifecycle().transition(envelope.envelopeId(), EnvelopeStatus.COMPLETED, "COMPLETED", brokerId);
            }
        } catch (Throwable failure) {
            // Errors get the same treatment as exceptions. Before this, a LinkageError from a native backend — the
            // case this codebase actually hits — left the envelope in RUNNING forever: no terminal state means the
            // trace is never reclaimed, and nothing anywhere said the handler had died.
            runtime.handleFailure(envelope, "HANDLER_EXCEPTION", failure.getMessage(), failure);
            if (failure instanceof Error fatal) {
                throw fatal;
            }
        }
    }

    @Override
    public BrokerSnapshot snapshot() {
        return new BrokerSnapshot(brokerId, queue.size(), running.size(), new ArrayList<>(running.keySet()));
    }

    @Override
    public void cancel(String envelopeId, String reasonCode, String message) {
        BrokerTask runningTask = running.get(envelopeId);
        if (runningTask != null) {
            runningTask.runtime().cancellation().cancelSelf(envelopeId, reasonCode, message);
        }
        queue.removeIf(task -> task.envelope().envelopeId().equals(envelopeId));
        java.util.concurrent.atomic.AtomicReference<ProtocolTaskHandle> holder = handles.get(envelopeId);
        ProtocolTaskHandle handle = holder == null ? null : holder.get();
        if (handle != null && interruptsRunningTasks()) {
            // Only the broker that promises interruption interrupts; for the others the cancelled status above is the
            // contract, and an interrupt would reach into a handler that never agreed to be stopped.
            handle.cancel(reasonCode);
        }
    }

    @Override
    public String brokerId() {
        return brokerId;
    }

    protected List<BrokerTask> drainQueue() {
        List<BrokerTask> tasks = new ArrayList<>();
        BrokerTask task;
        while ((task = queue.poll()) != null) tasks.add(task);
        return tasks;
    }
}
