package com.rheinmetal.tianshu.protocol.adapter;

import com.rheinmetal.tianshu.protocol.CancellationScope;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.FailurePolicy;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;

public record AdapterDefaults(
        Priority priority,
        ThreadPolicy threadPolicy,
        DeliveryPolicy deliveryPolicy,
        CancellationScope cancellationScope,
        FailurePolicy failurePolicy,
        long deadlineMs,
        long expireMs,
        int maxConcurrency,
        int queueCapacity,
        boolean cancellable,
        boolean supportsStreaming
) {
    public AdapterDefaults {
        if (priority == null) priority = Priority.NORMAL;
        if (threadPolicy == null) threadPolicy = ThreadPolicy.ASYNC_WORKER;
        if (deliveryPolicy == null) deliveryPolicy = DeliveryPolicy.WAIT_IN_QUEUE;
        if (cancellationScope == null) cancellationScope = CancellationScope.SELF_ONLY;
        if (failurePolicy == null) failurePolicy = FailurePolicy.REPORT_ONLY;
        if (deadlineMs <= 0L) deadlineMs = 30_000L;
        if (expireMs <= 0L) expireMs = Math.max(deadlineMs + 30_000L, 60_000L);
        maxConcurrency = Math.max(1, maxConcurrency);
        queueCapacity = Math.max(1, queueCapacity);
    }

    public static AdapterDefaults standard() {
        return new AdapterDefaults(Priority.NORMAL, ThreadPolicy.ASYNC_WORKER, DeliveryPolicy.WAIT_IN_QUEUE, CancellationScope.SELF_ONLY, FailurePolicy.REPORT_ONLY, 30_000L, 60_000L, Runtime.getRuntime().availableProcessors(), 64, true, false);
    }

    public static AdapterDefaults mainThreadUi() {
        return new AdapterDefaults(Priority.NORMAL, ThreadPolicy.MUST_MAIN, DeliveryPolicy.WAIT_IN_QUEUE, CancellationScope.SELF_ONLY, FailurePolicy.REPORT_ONLY, 5_000L, 10_000L, 1, 32, true, false);
    }

    public static AdapterDefaults highFrequencyFact() {
        return new AdapterDefaults(Priority.LOW, ThreadPolicy.ASYNC_WORKER, DeliveryPolicy.LATEST_ONLY, CancellationScope.SELF_ONLY, FailurePolicy.REPORT_ONLY, 1_000L, 3_000L, 1, 8, true, false);
    }

    public AdapterDefaults withPriority(Priority value) {
        return new AdapterDefaults(value, threadPolicy, deliveryPolicy, cancellationScope, failurePolicy, deadlineMs, expireMs, maxConcurrency, queueCapacity, cancellable, supportsStreaming);
    }

    public AdapterDefaults withThreadPolicy(ThreadPolicy value) {
        return new AdapterDefaults(priority, value, deliveryPolicy, cancellationScope, failurePolicy, deadlineMs, expireMs, maxConcurrency, queueCapacity, cancellable, supportsStreaming);
    }

    public AdapterDefaults withDeliveryPolicy(DeliveryPolicy value) {
        return new AdapterDefaults(priority, threadPolicy, value, cancellationScope, failurePolicy, deadlineMs, expireMs, maxConcurrency, queueCapacity, cancellable, supportsStreaming);
    }

    public AdapterDefaults withTiming(long deadlineMs, long expireMs) {
        return new AdapterDefaults(priority, threadPolicy, deliveryPolicy, cancellationScope, failurePolicy, deadlineMs, expireMs, maxConcurrency, queueCapacity, cancellable, supportsStreaming);
    }
}
