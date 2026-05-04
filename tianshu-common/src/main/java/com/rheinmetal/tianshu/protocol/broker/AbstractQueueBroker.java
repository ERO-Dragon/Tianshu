package com.rheinmetal.tianshu.protocol.broker;

import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.registry.HandlerRegistration;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractQueueBroker implements ProtocolBroker {
    protected final String brokerId;
    protected final int queueCapacity;
    protected final int maxConcurrency;
    protected final PriorityBlockingQueue<BrokerTask> queue = new PriorityBlockingQueue<>();
    protected final Map<String, BrokerTask> running = new ConcurrentHashMap<>();
    protected final ExecutorService executor;
    protected final AtomicBoolean draining = new AtomicBoolean(false);

    protected AbstractQueueBroker(String brokerId, int queueCapacity, int maxConcurrency) {
        this.brokerId = brokerId;
        this.queueCapacity = Math.max(1, queueCapacity);
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.executor = Executors.newFixedThreadPool(this.maxConcurrency, runnable -> {
            Thread thread = new Thread(runnable, "Tianshu-Protocol-" + brokerId);
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public BrokerSubmitResult submit(TianshuEnvelope envelope, HandlerRegistration registration, ProtocolRuntime runtime) {
        if (queue.size() >= queueCapacity && shouldRejectWhenFull(envelope)) {
            runtime.lifecycle().transition(envelope.envelopeId(), EnvelopeStatus.REJECTED, "BROKER_QUEUE_FULL", "Broker queue is full");
            return BrokerSubmitResult.rejected("BROKER_QUEUE_FULL", "Broker queue is full");
        }
        enqueue(new BrokerTask(envelope, registration, runtime));
        runtime.lifecycle().transition(envelope.envelopeId(), EnvelopeStatus.QUEUED, "QUEUED", brokerId);
        drain();
        return BrokerSubmitResult.accept();
    }

    protected boolean shouldRejectWhenFull(TianshuEnvelope envelope) {
        return true;
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
                executor.submit(() -> runTask(task));
            }
        } finally {
            draining.set(false);
            if (!queue.isEmpty() && running.size() < maxConcurrency) drain();
        }
    }

    protected void runTask(BrokerTask task) {
        TianshuEnvelope envelope = task.envelope();
        ProtocolRuntime runtime = task.runtime();
        try {
            if (runtime.cancellation().isCancelled(envelope.envelopeId())) return;
            runtime.lifecycle().transition(envelope.envelopeId(), EnvelopeStatus.RUNNING, "RUNNING", brokerId);
            task.registration().handler().handle(envelope, runtime.context());
            if (task.registration().capabilityDescriptor().completionPolicy() == CompletionPolicy.AUTO_COMPLETE_ON_RETURN && !runtime.cancellation().isCancelled(envelope.envelopeId())) {
                runtime.lifecycle().transition(envelope.envelopeId(), EnvelopeStatus.COMPLETED, "COMPLETED", brokerId);
            }
        } catch (Exception exception) {
            runtime.handleFailure(envelope, "HANDLER_EXCEPTION", exception.getMessage(), exception);
        } finally {
            running.remove(envelope.envelopeId());
            drain();
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
