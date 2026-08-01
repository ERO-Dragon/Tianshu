package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsCodecExecution;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ModuleExecutionAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class TtsProtocolCodecExecution implements TtsCodecExecution {
    private static final String MODULE_ID = "module.tts";
    private static final String CONCURRENCY_KEY = MODULE_ID + ":moss-codec";
    private static final long AWAIT_POLL_MILLIS = 25L;

    private final ModuleExecutionAccess execution;

    public TtsProtocolCodecExecution(ModuleExecutionAccess execution) {
        this.execution = Objects.requireNonNull(execution, "execution");
    }

    @Override
    public Task submit(Runnable work) {
        Objects.requireNonNull(work, "work");
        CompletableFuture<Void> completion = new CompletableFuture<>();
        AtomicBoolean started = new AtomicBoolean();
        ProtocolTaskSpec spec = ProtocolTaskSpec.builder()
                .moduleId(MODULE_ID)
                .lane(ExecutionLane.TTS_FAST)
                .priority(Priority.HIGH)
                .concurrencyKey(CONCURRENCY_KEY)
                .maxConcurrency(1)
                .queueCapacity(2)
                .interruptible(true)
                .build();
        ProtocolTaskHandle handle = execution.submit(spec, () -> {
            started.set(true);
            try {
                work.run();
                completion.complete(null);
            } catch (RuntimeException | Error failure) {
                completion.completeExceptionally(failure);
                throw failure;
            }
        });
        if (handle.state() == ProtocolTaskState.REJECTED) {
            completion.completeExceptionally(new RejectedExecutionException("TTS_MOSS_CODEC_EXECUTION_REJECTED"));
        }
        return new ProtocolCodecTask(handle, completion, started);
    }

    private record ProtocolCodecTask(
            ProtocolTaskHandle handle,
            CompletableFuture<Void> completion,
            AtomicBoolean started
    ) implements Task {
        @Override
        public boolean accepted() {
            return handle.state() != ProtocolTaskState.REJECTED;
        }

        @Override
        public void cancel(String reason) {
            handle.cancel(reason == null || reason.isBlank() ? "TTS_MOSS_CODEC_CANCELLED" : reason);
        }

        @Override
        public void await(BooleanSupplier cancellationRequested) throws Exception {
            BooleanSupplier cancellation = cancellationRequested == null ? () -> false : cancellationRequested;
            boolean cancellationIssued = false;
            while (true) {
                if (cancellation.getAsBoolean() && !cancellationIssued) {
                    cancel("TTS_MOSS_CODEC_CANCELLED");
                    cancellationIssued = true;
                }
                try {
                    completion.get(AWAIT_POLL_MILLIS, TimeUnit.MILLISECONDS);
                    if (cancellationIssued) {
                        throw new CancellationException("TTS_MOSS_CODEC_CANCELLED");
                    }
                    return;
                } catch (TimeoutException ignored) {
                    if (handle.isDone() && !completion.isDone()) {
                        if (!started.get()) {
                            throw new CancellationException("TTS_MOSS_CODEC_CANCELLED_BEFORE_START");
                        }
                        handle.failureCause().ifPresent(completion::completeExceptionally);
                    }
                } catch (ExecutionException failure) {
                    rethrow(failure.getCause());
                }
            }
        }

        private static void rethrow(Throwable failure) throws Exception {
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(failure);
        }
    }
}
