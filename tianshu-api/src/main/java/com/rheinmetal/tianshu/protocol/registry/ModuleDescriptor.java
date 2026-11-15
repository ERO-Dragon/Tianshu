package com.rheinmetal.tianshu.protocol.registry;

import com.rheinmetal.tianshu.protocol.CancellationScope;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.FailurePolicy;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;

import java.util.List;
import java.util.Objects;

/**
 * What a module declares about itself when it registers.
 *
 * <p><b>Which of these the runtime actually enforces</b>, so nobody again reads a declaration as a guarantee:</p>
 *
 * <ul>
 *   <li>Enforced: {@link #moduleId()}, {@link #capabilities()}, {@link #defaultThreadPolicy()} (the execution policy
 *       falls back to it), {@link #maxConcurrency()} and {@link #queueCapacity()} (the broker is created from them).</li>
 *   <li><b>Declarative only</b>: {@link #defaultCancellationScope()}, {@link #defaultFailurePolicy()},
 *       {@link #defaultDeliveryPolicy()}, {@link #cancellable()} and {@link #supportsStreaming()}. They describe the
 *       module and are worth reading for diagnosis, but nothing acts on them: the adapter stamps the cancellation
 *       scope, failure policy and delivery policy onto each envelope it builds from its own options, and delivery
 *       behaviour at runtime comes from {@link CapabilityDescriptor#requiredBrokerType()} —
 *       {@code LATEST_ONLY}/{@code COALESCE} are broker types, not delivery policies. Declaring
 *       {@code cancellable = false} here does not make a module uncancellable, and declaring a delivery policy here
 *       does not make the runtime coalesce anything.</li>
 * </ul>
 *
 * <p>The two sets are kept apart on purpose rather than unified: envelopes are built by the adapter, which already
 * applies these policies, so having the runtime read them a second time would give the same decision two sources of
 * truth. What was wrong was not their presence but that they read like settings.</p>
 */
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
    /** Declarative only — see the class javadoc. The adapter applies the policy it was given, not this value. */
    public DeliveryPolicy defaultDeliveryPolicy() { return defaultDeliveryPolicy; }
    /** Declarative only — see the class javadoc. Cancellation is decided by the envelope's {@code CancellationScope}. */
    public boolean cancellable() { return cancellable; }
    public boolean supportsStreaming() { return supportsStreaming; }
    public int maxConcurrency() { return maxConcurrency; }
    public int queueCapacity() { return queueCapacity; }
}
