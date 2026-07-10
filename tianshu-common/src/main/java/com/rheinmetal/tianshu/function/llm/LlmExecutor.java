package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.function.llm.service.RagPersistenceScheduler;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

final class LlmExecutor {

    private final ProtocolExecutorManager executorManager;

    LlmExecutor(ProtocolExecutorManager executorManager) {
        this.executorManager = Objects.requireNonNull(executorManager, "executorManager");
    }

    Executor cpuExecutor() {
        return task -> submitOrReject(spec(ExecutionLane.CPU, "llm.cpu", 2, 64), task);
    }

    Executor modelLoadExecutor() {
        return task -> submitOrReject(spec(ExecutionLane.MODEL_LOAD, "llm.model_load", 1, 2), task);
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
