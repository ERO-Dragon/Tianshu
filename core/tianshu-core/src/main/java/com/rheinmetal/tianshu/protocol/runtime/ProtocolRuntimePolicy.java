package com.rheinmetal.tianshu.protocol.runtime;

public final class ProtocolRuntimePolicy {
    private final ProtocolExecutorPolicy executorPolicy;
    private final int deadLetterCapacity;
    private final int defaultStormLimitPerSecond;
    private final int maxTraceDepth;
    private final long retainedEnvelopeMillis;
    private final int retentionSweepInterval;
    private final int maxRetainedTraces;

    private ProtocolRuntimePolicy(Builder builder) {
        this.executorPolicy = builder.executorPolicy == null ? ProtocolExecutorPolicy.defaults() : builder.executorPolicy;
        this.deadLetterCapacity = Math.max(32, builder.deadLetterCapacity);
        this.defaultStormLimitPerSecond = Math.max(1, builder.defaultStormLimitPerSecond);
        this.maxTraceDepth = Math.max(1, builder.maxTraceDepth);
        this.retainedEnvelopeMillis = Math.max(60_000L, builder.retainedEnvelopeMillis);
        this.retentionSweepInterval = Math.max(64, builder.retentionSweepInterval);
        this.maxRetainedTraces = Math.max(64, builder.maxRetainedTraces);
    }

    public static ProtocolRuntimePolicy defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public ProtocolExecutorPolicy executorPolicy() {
        return executorPolicy;
    }

    public int deadLetterCapacity() {
        return deadLetterCapacity;
    }

    public int defaultStormLimitPerSecond() {
        return defaultStormLimitPerSecond;
    }

    public int maxTraceDepth() {
        return maxTraceDepth;
    }

    /** How long a finished envelope stays queryable before retention may drop it. */
    public long retainedEnvelopeMillis() {
        return retainedEnvelopeMillis;
    }

    /** Envelopes accepted between two retention sweeps. */
    public int retentionSweepInterval() {
        return retentionSweepInterval;
    }

    /** Upper bound of traces kept when the age rule alone would keep more. */
    public int maxRetainedTraces() {
        return maxRetainedTraces;
    }

    public static final class Builder {
        private ProtocolExecutorPolicy executorPolicy;
        private int deadLetterCapacity = 512;
        private int defaultStormLimitPerSecond = 200;
        private int maxTraceDepth = 32;
        private long retainedEnvelopeMillis = 30L * 60L * 1000L;
        private int retentionSweepInterval = 1024;
        private int maxRetainedTraces = 8192;

        private Builder() {
        }

        public Builder executorPolicy(ProtocolExecutorPolicy executorPolicy) {
            this.executorPolicy = executorPolicy;
            return this;
        }

        public Builder deadLetterCapacity(int deadLetterCapacity) {
            this.deadLetterCapacity = deadLetterCapacity;
            return this;
        }

        public Builder defaultStormLimitPerSecond(int defaultStormLimitPerSecond) {
            this.defaultStormLimitPerSecond = defaultStormLimitPerSecond;
            return this;
        }

        public Builder maxTraceDepth(int maxTraceDepth) {
            this.maxTraceDepth = maxTraceDepth;
            return this;
        }

        /** How long a finished envelope stays queryable; the default keeps half an hour of history. */
        public Builder retainedEnvelopeMillis(long retainedEnvelopeMillis) {
            this.retainedEnvelopeMillis = retainedEnvelopeMillis;
            return this;
        }

        /** Envelopes accepted between two retention sweeps; the sweep never runs per-envelope. */
        public Builder retentionSweepInterval(int retentionSweepInterval) {
            this.retentionSweepInterval = retentionSweepInterval;
            return this;
        }

        /** Upper bound of traces kept when the age rule alone would keep more. */
        public Builder maxRetainedTraces(int maxRetainedTraces) {
            this.maxRetainedTraces = maxRetainedTraces;
            return this;
        }

        public ProtocolRuntimePolicy build() {
            return new ProtocolRuntimePolicy(this);
        }
    }
}
