package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.CancellationScope;
import com.rheinmetal.tianshu.protocol.Priority;

import java.util.Objects;
import java.util.UUID;

public final class ProtocolTaskSpec {
    private final String taskId;
    private final String moduleId;
    private final String envelopeId;
    private final ExecutionLane lane;
    private final Priority priority;
    private final CancellationScope cancellationScope;
    private final String concurrencyKey;
    private final int maxConcurrency;
    private final int queueCapacity;
    private final boolean interruptible;

    private ProtocolTaskSpec(Builder builder) {
        this.taskId = normalizeOptional(builder.taskId, UUID.randomUUID().toString());
        this.moduleId = normalizeRequired(builder.moduleId, "moduleId");
        this.envelopeId = normalizeOptional(builder.envelopeId, null);
        this.lane = builder.lane == null ? ExecutionLane.CPU : builder.lane;
        this.priority = builder.priority == null ? Priority.NORMAL : builder.priority;
        this.cancellationScope = builder.cancellationScope == null ? CancellationScope.SELF_ONLY : builder.cancellationScope;
        this.concurrencyKey = normalizeOptional(builder.concurrencyKey, this.moduleId + ":" + this.lane.name());
        this.maxConcurrency = Math.max(1, builder.maxConcurrency);
        this.queueCapacity = Math.max(1, builder.queueCapacity);
        this.interruptible = builder.interruptible;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String taskId() {
        return taskId;
    }

    public String moduleId() {
        return moduleId;
    }

    public String envelopeId() {
        return envelopeId;
    }

    public ExecutionLane lane() {
        return lane;
    }

    public Priority priority() {
        return priority;
    }

    public CancellationScope cancellationScope() {
        return cancellationScope;
    }

    public String concurrencyKey() {
        return concurrencyKey;
    }

    public int maxConcurrency() {
        return maxConcurrency;
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    public boolean interruptible() {
        return interruptible;
    }

    private static String normalizeRequired(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    public static final class Builder {
        private String taskId;
        private String moduleId;
        private String envelopeId;
        private ExecutionLane lane;
        private Priority priority;
        private CancellationScope cancellationScope;
        private String concurrencyKey;
        private int maxConcurrency = 1;
        private int queueCapacity = 64;
        private boolean interruptible = true;

        private Builder() {
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder moduleId(String moduleId) {
            this.moduleId = moduleId;
            return this;
        }

        public Builder envelopeId(String envelopeId) {
            this.envelopeId = envelopeId;
            return this;
        }

        public Builder lane(ExecutionLane lane) {
            this.lane = Objects.requireNonNull(lane, "lane");
            return this;
        }

        public Builder priority(Priority priority) {
            this.priority = priority;
            return this;
        }

        public Builder cancellationScope(CancellationScope cancellationScope) {
            this.cancellationScope = cancellationScope;
            return this;
        }

        public Builder concurrencyKey(String concurrencyKey) {
            this.concurrencyKey = concurrencyKey;
            return this;
        }

        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        public Builder interruptible(boolean interruptible) {
            this.interruptible = interruptible;
            return this;
        }

        public ProtocolTaskSpec build() {
            return new ProtocolTaskSpec(this);
        }
    }
}
