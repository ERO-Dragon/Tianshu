package com.rheinmetal.tianshu.client.host;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public interface ClientScheduler {
    void execute(Runnable task);

    boolean isOnMainThread();

    /** Runs a value-producing task on the client thread, immediately when already there. */
    default <T> CompletableFuture<T> supply(Supplier<T> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable invocation = () -> {
            try {
                result.complete(task.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        };
        try {
            if (isOnMainThread()) {
                invocation.run();
            } else {
                execute(invocation);
            }
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }
}
