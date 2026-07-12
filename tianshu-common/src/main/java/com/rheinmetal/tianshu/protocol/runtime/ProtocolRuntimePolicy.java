package com.rheinmetal.tianshu.protocol.runtime;

public final class ProtocolRuntimePolicy {
    private final ProtocolExecutorPolicy executorPolicy;
    private final int deadLetterCapacity;
    private final int defaultStormLimitPerSecond;
    private final int maxTraceDepth;

    private ProtocolRuntimePolicy(Builder builder) {
        this.executorPolicy = builder.executorPolicy == null ? ProtocolExecutorPolicy.defaults() : builder.executorPolicy;
        this.deadLetterCapacity = Math.max(32, builder.deadLetterCapacity);
        this.defaultStormLimitPerSecond = Math.max(1, builder.defaultStormLimitPerSecond);
        this.maxTraceDepth = Math.max(1, builder.maxTraceDepth);
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

    public static final class Builder {
        private ProtocolExecutorPolicy executorPolicy;
        private int deadLetterCapacity = 512;
        private int defaultStormLimitPerSecond = 200;
        private int maxTraceDepth = 32;

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

        public ProtocolRuntimePolicy build() {
            return new ProtocolRuntimePolicy(this);
        }
    }
}
