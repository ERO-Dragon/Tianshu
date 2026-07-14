package com.rheinmetal.tianshu.core.runtime;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Serial command boundary for Core lifecycle work that must never run on the Minecraft main thread. */
final class CoreLifecycleCommandQueue implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Tianshu-Core-Lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();

    <T> CompletableFuture<T> submit(String commandName, Supplier<T> command) {
        String name = Objects.requireNonNull(commandName, "commandName");
        Supplier<T> action = Objects.requireNonNull(command, "command");
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed.get()) {
            result.completeExceptionally(new RejectedExecutionException("Core lifecycle queue is closed: " + name));
            return result;
        }
        try {
            executor.execute(() -> {
                try {
                    result.complete(action.get());
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                } catch (Error failure) {
                    result.completeExceptionally(failure);
                    throw failure;
                }
            });
        } catch (RejectedExecutionException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdown();
        }
    }
}
