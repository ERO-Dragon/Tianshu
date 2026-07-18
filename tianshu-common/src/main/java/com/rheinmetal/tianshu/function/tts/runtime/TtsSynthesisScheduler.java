package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisEngine;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ModuleExecutionAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;

import java.time.Duration;
import java.util.Objects;

public final class TtsSynthesisScheduler {
    private static final String MODULE_ID = "module.tts";
    static final String BACKEND_CONCURRENCY_KEY = MODULE_ID + ":backend";

    private final ModuleExecutionAccess executorManager;
    private final TtsSynthesisEngine synthesisEngine;
    private final Object ownershipLock = new Object();
    private Object activeOwner;

    public TtsSynthesisScheduler(ModuleExecutionAccess executorManager, TtsSynthesisEngine synthesisEngine) {
        this.executorManager = Objects.requireNonNull(executorManager, "executorManager");
        this.synthesisEngine = Objects.requireNonNull(synthesisEngine, "synthesisEngine");
    }

    public ProtocolTaskHandle submit(TtsRequest request, Object owner, Runnable task) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(task, "task");
        ExecutionLane lane = lane();
        ProtocolTaskSpec spec = ProtocolTaskSpec.builder()
                .moduleId(MODULE_ID)
                .lane(lane)
                .envelopeId(request == null ? "" : request.envelopeId())
                .priority(priorityFor(request))
                .concurrencyKey(BACKEND_CONCURRENCY_KEY)
                .maxConcurrency(1)
                .queueCapacity(8)
                .build();
        return executorManager.submit(spec, () -> runOwned(owner, task));
    }

    public ProtocolTaskHandle scheduleTimeout(TtsRequest request, Duration delay, Runnable task) {
        ProtocolTaskSpec spec = ProtocolTaskSpec.builder()
                .moduleId(MODULE_ID)
                .lane(ExecutionLane.SCHEDULED)
                .envelopeId(request == null ? "" : request.envelopeId())
                .priority(Priority.CRITICAL)
                .concurrencyKey(MODULE_ID + ":synthesis-timeout")
                .maxConcurrency(1)
                .queueCapacity(64)
                .interruptible(true)
                .build();
        return executorManager.schedule(spec, task, delay);
    }

    public boolean interrupt(Object owner) {
        if (owner == null) {
            return false;
        }
        synchronized (ownershipLock) {
            if (activeOwner != owner) {
                return false;
            }
            synthesisEngine.interrupt();
            return true;
        }
    }

    public ExecutionLane lane() {
        return synthesisEngine.isAutoregressive() ? ExecutionLane.TTS_AUTOREGRESSIVE : ExecutionLane.TTS_FAST;
    }

    public static Priority priorityFor(TtsRequest request) {
        Priority base = request == null ? Priority.NORMAL : request.priority();
        TtsPlaybackPolicy policy = request == null ? TtsPlaybackPolicy.QUEUE : request.playbackPolicy();
        return switch (policy) {
            case CANCEL_SENTENCE_AND_PLAY,
                 CANCEL_SESSION_AND_PLAY,
                 REPLACE_CURRENT,
                 LATEST_ONLY -> base.atLeast(Priority.CRITICAL) ? base : Priority.CRITICAL;
            case INSERT_AFTER_SENTENCE,
                 INSERT_AFTER_SESSION -> base.atLeast(Priority.HIGH) ? base : Priority.HIGH;
            case QUEUE,
                 DROP_IF_BUSY -> base;
        };
    }

    private void runOwned(Object owner, Runnable task) {
        synchronized (ownershipLock) {
            if (activeOwner != null) {
                throw new IllegalStateException("TTS backend work ownership overlapped");
            }
            activeOwner = owner;
        }
        try {
            task.run();
        } finally {
            synchronized (ownershipLock) {
                if (activeOwner == owner) {
                    activeOwner = null;
                }
            }
        }
    }
}
