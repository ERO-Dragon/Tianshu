package com.rheinmetal.tianshu.function.weaving;

import com.rheinmetal.tianshu.function.weaving.service.RagPersistenceScheduler;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ModuleExecutionAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

final class LlmExecutor {

    private final ModuleExecutionAccess executorManager;

    LlmExecutor(ModuleExecutionAccess executorManager) {
        this.executorManager = Objects.requireNonNull(executorManager, "executorManager");
    }

    Executor cpuExecutor() {
        return task -> submitOrReject(spec(ExecutionLane.CPU, "llm.cpu", 2, 64), task);
    }

    Executor modelLoadExecutor() {
        return task -> submitOrReject(spec(ExecutionLane.MODEL_LOAD, "llm.model_load", 1, 2), task);
    }

    /**
     * The model-load lane, entered only after {@code delay} has elapsed.
     *
     * <p>Used for the embedding warm-up so a native model load never starts inside the world-entry window:
     * it maps a few hundred megabytes and initialises a backend, which on world entry would land on top of
     * chunk loading and the first frames. Nothing waits on this — a RAG write that arrives earlier still
     * loads the model on demand, on the lane that write already runs on.</p>
     */
    Executor delayedModelLoadExecutor(Duration delay) {
        return task -> {
            var handle = executorManager.schedule(
                    spec(ExecutionLane.SCHEDULED, "llm.model_load.delay", 1, 2),
                    () -> submitOrReject(spec(ExecutionLane.MODEL_LOAD, "llm.model_load", 1, 2), task),
                    delay
            );
            if (handle.state() == ProtocolTaskState.REJECTED) {
                throw new RejectedExecutionException("Protocol executor rejected the scheduled embedding model load");
            }
        };
    }

    RagPersistenceScheduler ragPersistenceScheduler() {
        return new RagPersistenceScheduler() {
            @Override
            public void schedule(Runnable task, Duration delay) {
                var handle = executorManager.schedule(
                        spec(ExecutionLane.SCHEDULED, "llm.rag.persistence.timer", 1, 64),
                        () -> submitOrReject(spec(ExecutionLane.IO, "llm.rag.persistence", 1, 64), task),
                        delay
                );
                if (handle.state() == ProtocolTaskState.REJECTED) {
                    throw new RejectedExecutionException("Protocol executor rejected scheduled LLM RAG persistence task");
                }
            }
        };
    }

    private void submitOrReject(ProtocolTaskSpec spec, Runnable task) {
        var handle = executorManager.submit(spec, task);
        if (handle.state() == ProtocolTaskState.REJECTED) {
            throw new RejectedExecutionException("Protocol executor rejected LLM task: " + spec.concurrencyKey());
        }
    }

    private static ProtocolTaskSpec spec(ExecutionLane lane, String concurrencyKey, int maxConcurrency, int queueCapacity) {
        return ProtocolTaskSpec.builder()
                .moduleId(LlmProtocolAdapter.MODULE_ID)
                .lane(lane)
                .priority(Priority.NORMAL)
                .concurrencyKey(concurrencyKey)
                .maxConcurrency(maxConcurrency)
                .queueCapacity(queueCapacity)
                .interruptible(true)
                .build();
    }
}
