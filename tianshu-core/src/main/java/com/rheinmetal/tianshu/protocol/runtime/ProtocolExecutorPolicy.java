package com.rheinmetal.tianshu.protocol.runtime;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class ProtocolExecutorPolicy {
    private final Map<ExecutionLane, LanePolicy> lanes;
    private final int scheduledThreads;

    private ProtocolExecutorPolicy(Builder builder) {
        this.lanes = Map.copyOf(builder.lanes);
        this.scheduledThreads = Math.max(1, builder.scheduledThreads);
    }

    public static ProtocolExecutorPolicy defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public LanePolicy lane(ExecutionLane lane) {
        LanePolicy policy = lanes.get(lane);
        if (policy == null) {
            throw new IllegalArgumentException("No executor policy for lane: " + lane);
        }
        return policy;
    }

    public int scheduledThreads() {
        return scheduledThreads;
    }

    public static final class LanePolicy {
        private final int threads;
        private final int queueCapacity;

        private LanePolicy(int threads, int queueCapacity) {
            this.threads = Math.max(1, threads);
            this.queueCapacity = Math.max(1, queueCapacity);
        }

        public int threads() {
            return threads;
        }

        public int queueCapacity() {
            return queueCapacity;
        }
    }

    public static final class Builder {
        private final EnumMap<ExecutionLane, LanePolicy> lanes = new EnumMap<>(ExecutionLane.class);
        private int scheduledThreads = 1;

        private Builder() {
            int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
            lane(ExecutionLane.CPU, Math.max(1, processors / 2), 64);
            lane(ExecutionLane.IO, Math.max(2, Math.min(4, processors / 2)), 64);
            lane(ExecutionLane.AUDIO_IO, 1, 128);
            lane(ExecutionLane.TTS_FAST, 1, 4);
            lane(ExecutionLane.TTS_AUTOREGRESSIVE, 1, 1);
            lane(ExecutionLane.ASR_STREAM, 1, 8);
            lane(ExecutionLane.MODEL_LOAD, 1, 2);
            lane(ExecutionLane.LONG, 2, 8);
        }

        public Builder lane(ExecutionLane lane, int threads, int queueCapacity) {
            Objects.requireNonNull(lane, "lane");
            if (lane == ExecutionLane.MAIN || lane == ExecutionLane.SCHEDULED) {
                throw new IllegalArgumentException("Lane " + lane + " does not use fixed executor policy");
            }
            lanes.put(lane, new LanePolicy(threads, queueCapacity));
            return this;
        }

        public Builder scheduledThreads(int scheduledThreads) {
            this.scheduledThreads = scheduledThreads;
            return this;
        }

        public ProtocolExecutorPolicy build() {
            return new ProtocolExecutorPolicy(this);
        }
    }
}
