package com.rheinmetal.tianshu.protocol.registry;

import com.rheinmetal.tianshu.protocol.CancellationScope;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.FailurePolicy;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;

import java.util.List;
import java.util.Objects;

public final class ModuleDescriptor {
    private final String moduleId;
    private final List<CapabilityDescriptor> capabilities;
    private final ThreadPolicy defaultThreadPolicy;
    private final CancellationScope defaultCancellationScope;
    private final FailurePolicy defaultFailurePolicy;
    private final DeliveryPolicy defaultDeliveryPolicy;
    private final boolean cancellable;
    private final boolean supportsStreaming;
    private final int maxConcurrency;
    private final int queueCapacity;

    public ModuleDescriptor(String moduleId, List<CapabilityDescriptor> capabilities, ThreadPolicy defaultThreadPolicy, CancellationScope defaultCancellationScope, FailurePolicy defaultFailurePolicy, DeliveryPolicy defaultDeliveryPolicy, boolean cancellable, boolean supportsStreaming, int maxConcurrency, int queueCapacity) {
        if (moduleId == null || moduleId.isBlank()) {
            throw new IllegalArgumentException("moduleId cannot be blank");
        }
        this.moduleId = moduleId;
        this.capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        this.defaultThreadPolicy = Objects.requireNonNull(defaultThreadPolicy, "defaultThreadPolicy");
        this.defaultCancellationScope = Objects.requireNonNull(defaultCancellationScope, "defaultCancellationScope");
        this.defaultFailurePolicy = Objects.requireNonNull(defaultFailurePolicy, "defaultFailurePolicy");
        this.defaultDeliveryPolicy = Objects.requireNonNull(defaultDeliveryPolicy, "defaultDeliveryPolicy");
        this.cancellable = cancellable;
        this.supportsStreaming = supportsStreaming;
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.queueCapacity = Math.max(1, queueCapacity);
    }

    public String moduleId() { return moduleId; }
    public List<CapabilityDescriptor> capabilities() { return capabilities; }
    public ThreadPolicy defaultThreadPolicy() { return defaultThreadPolicy; }
    public CancellationScope defaultCancellationScope() { return defaultCancellationScope; }
    public FailurePolicy defaultFailurePolicy() { return defaultFailurePolicy; }
    public DeliveryPolicy defaultDeliveryPolicy() { return defaultDeliveryPolicy; }
    public boolean cancellable() { return cancellable; }
    public boolean supportsStreaming() { return supportsStreaming; }
    public int maxConcurrency() { return maxConcurrency; }
    public int queueCapacity() { return queueCapacity; }
}
