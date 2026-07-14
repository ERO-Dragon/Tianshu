package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisEngine;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ModuleExecutionAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;

import java.util.Objects;

public final class TtsSynthesisScheduler {
    private static final String MODULE_ID = "module.tts";

    private final ModuleExecutionAccess executorManager;
    private final TtsSynthesisEngine synthesisEngine;

    public TtsSynthesisScheduler(ModuleExecutionAccess executorManager, TtsSynthesisEngine synthesisEngine) {
        this.executorManager = Objects.requireNonNull(executorManager, "executorManager");
        this.synthesisEngine = Objects.requireNonNull(synthesisEngine, "synthesisEngine");
    }

    public ProtocolTaskHandle submit(TtsRequest request, Runnable task) {
        ExecutionLane lane = lane();
        ProtocolTaskSpec spec = ProtocolTaskSpec.builder()
                .moduleId(MODULE_ID)
                .lane(lane)
                .envelopeId(request == null ? "" : request.envelopeId())
                .priority(priorityFor(request))
                .concurrencyKey(MODULE_ID + ":synthesis:" + (lane == ExecutionLane.TTS_AUTOREGRESSIVE ? "autoregressive" : "fast"))
                .maxConcurrency(1)
                .queueCapacity(8)
                .build();
        return executorManager.submit(spec, task);
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
}
