package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CancellationScope;
import com.rheinmetal.tianshu.protocol.DeadLetterPolicy;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.FailurePolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.TargetMode;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.broker.BrokerRegistry;
import com.rheinmetal.tianshu.protocol.broker.BrokerSubmitResult;
import com.rheinmetal.tianshu.protocol.broker.ProtocolBroker;
import com.rheinmetal.tianshu.protocol.integration.IntegrationModuleRegistry;
import com.rheinmetal.tianshu.protocol.payload.RuntimeInterruptPayload;
import com.rheinmetal.tianshu.protocol.payload.CancelPayload;
import com.rheinmetal.tianshu.protocol.payload.ModuleStatusPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityRegistry;
import com.rheinmetal.tianshu.protocol.registry.HandlerRegistration;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleRegistry;
import com.rheinmetal.tianshu.protocol.registry.ResponseHandlerRegistry;
import com.rheinmetal.tianshu.protocol.registry.TopicDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicRegistry;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionRegistry;
import com.rheinmetal.tianshu.protocol.registry.ValidationResult;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistry;

import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;

public final class ProtocolRuntime implements ModuleProtocolAccess, RuntimeInterruptPublisher, AutoCloseable {
    private static final String RUNTIME_SOURCE_ID = "core.runtime";

    private final ModuleRegistry moduleRegistry = new ModuleRegistry();
    private final CapabilityRegistry capabilityRegistry = new CapabilityRegistry();
    private final ResponseHandlerRegistry responseHandlerRegistry = new ResponseHandlerRegistry();
    private final TopicRegistry topicRegistry = new TopicRegistry();
    private final TopicSubscriptionRegistry topicSubscriptionRegistry = new TopicSubscriptionRegistry();
    private final EnvelopeLifecycleStore lifecycleStore = new EnvelopeLifecycleStore();
    private final CancellationRegistry cancellationRegistry = new CancellationRegistry(lifecycleStore);
    private final DeadLetterQueue deadLetterQueue;
    private final StormGuard stormGuard;
    private final VoiceTriggerRegistry voiceTriggerRegistry;
    private final IntegrationModuleRegistry integrationModuleRegistry = new IntegrationModuleRegistry();
    private final ModuleStatusCache moduleStatusCache = new ModuleStatusCache();
    private final ProtocolExecutorManager executorManager;
    private final BrokerRegistry brokerRegistry;
    private final ProtocolContext context;

    public ProtocolRuntime(MainThreadExecutor mainThreadExecutor) {
        this(mainThreadExecutor, new VoiceTriggerRegistry());
    }

    public ProtocolRuntime(MainThreadExecutor mainThreadExecutor, VoiceTriggerRegistry voiceTriggerRegistry) {
        this(mainThreadExecutor, voiceTriggerRegistry, ProtocolRuntimePolicy.defaults());
    }

    public ProtocolRuntime(MainThreadExecutor mainThreadExecutor, ProtocolRuntimePolicy runtimePolicy) {
        this(mainThreadExecutor, new VoiceTriggerRegistry(), runtimePolicy);
    }

    public ProtocolRuntime(MainThreadExecutor mainThreadExecutor, VoiceTriggerRegistry voiceTriggerRegistry, ProtocolRuntimePolicy runtimePolicy) {
        ProtocolRuntimePolicy effectivePolicy = runtimePolicy == null ? ProtocolRuntimePolicy.defaults() : runtimePolicy;
        this.deadLetterQueue = new DeadLetterQueue(effectivePolicy.deadLetterCapacity(), lifecycleStore);
        this.stormGuard = new StormGuard(effectivePolicy.defaultStormLimitPerSecond(), effectivePolicy.maxTraceDepth());
        this.voiceTriggerRegistry = voiceTriggerRegistry == null ? new VoiceTriggerRegistry() : voiceTriggerRegistry;
        this.executorManager = new ProtocolExecutorManager(mainThreadExecutor, effectivePolicy.executorPolicy());
        this.brokerRegistry = new BrokerRegistry(mainThreadExecutor, executorManager);
        this.context = new RuntimeContext();
        subscribeModuleStatusCache();
    }

    public void registerModule(ModuleDescriptor descriptor, com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler handler) {
        moduleRegistry.register(descriptor);
        capabilityRegistry.register(descriptor, handler);
    }

    public void registerTopic(TopicDescriptor descriptor) {
        topicRegistry.register(descriptor);
    }

    public void registerResponseHandler(String requestEnvelopeId, ModuleDescriptor descriptor, com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor capabilityDescriptor, com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler handler) {
        moduleRegistry.register(descriptor);
        responseHandlerRegistry.register(requestEnvelopeId, descriptor, capabilityDescriptor, handler);
    }

    public void unregisterResponseHandlers(String requestEnvelopeId) {
        responseHandlerRegistry.unregisterRequest(requestEnvelopeId);
    }

    public void subscribeTopic(ModuleDescriptor moduleDescriptor, TopicSubscriptionDescriptor descriptor, com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler handler) {
        moduleRegistry.register(moduleDescriptor);
        topicSubscriptionRegistry.subscribe(moduleDescriptor, descriptor, handler);
    }

    public void unregisterModule(String moduleId) {
        if (moduleId == null || moduleId.isBlank()) {
            return;
        }
        String normalizedModuleId = moduleId.trim();
        capabilityRegistry.unregisterModule(normalizedModuleId);
        responseHandlerRegistry.unregisterModule(normalizedModuleId);
        topicSubscriptionRegistry.unregisterModule(normalizedModuleId);
        voiceTriggerRegistry.unregisterModule(normalizedModuleId);
        moduleRegistry.unregisterModule(normalizedModuleId);
    }

    public void submit(TianshuEnvelope envelope) {
        long now = System.currentTimeMillis();
        if (envelope.header().isExpired(now)) {
            lifecycleStore.accept(envelope);
            deadLetterQueue.add(envelope, "ENVELOPE_EXPIRED", "Envelope expired before dispatch", DeadLetterPolicy.LOG_ONLY);
            return;
        }
        lifecycleStore.accept(envelope);
        int depth = traceDepth(envelope);
        int topicLimit = topicRegistry.find(envelope.header().target()).map(TopicDescriptor::stormLimitPerSecond).orElse(0);
        GuardResult guardResult = stormGuard.check(envelope, depth, topicLimit);
        if (!guardResult.accepted()) {
            deadLetterQueue.add(envelope, guardResult.code(), guardResult.message(), DeadLetterPolicy.LOG_ONLY);
            return;
        }
        if (envelope.header().packetType() == PacketType.CANCEL) {
            handleCancelEnvelope(envelope);
            return;
        }
        List<HandlerRegistration> registrations = resolveHandlers(envelope);
        if (registrations.isEmpty()) {
            deadLetterQueue.add(envelope, "TARGET_NOT_FOUND", "No registered handler for target", DeadLetterPolicy.LOG_ONLY);
            return;
        }
        boolean childDelivery = registrations.size() > 1 || envelope.header().targetMode() == TargetMode.TOPIC;
        for (HandlerRegistration registration : registrations) {
            TianshuEnvelope deliveryEnvelope = createDeliveryEnvelope(envelope, childDelivery);
            if (deliveryEnvelope != envelope) {
                lifecycleStore.accept(deliveryEnvelope);
            }
            dispatchToRegistration(deliveryEnvelope, registration);
        }
        if (childDelivery) {
            lifecycleStore.transition(envelope.envelopeId(), EnvelopeStatus.COMPLETED, "ROUTED", "Envelope routed to " + registrations.size() + " handler(s)");
        }
    }

    public ProtocolTaskHandle submitTask(ProtocolTaskSpec spec, Runnable task) {
        return executorManager.submit(spec, task);
    }

    private TianshuEnvelope createDeliveryEnvelope(TianshuEnvelope sourceEnvelope, boolean forceChildDelivery) {
        if (!forceChildDelivery) {
            return sourceEnvelope;
        }
        return EnvelopeBuilder.childOf(sourceEnvelope)
            .sourceId(sourceEnvelope.header().sourceId())
            .targetMode(sourceEnvelope.header().targetMode())
            .target(sourceEnvelope.header().target())
            .deliveryPolicy(sourceEnvelope.header().deliveryPolicy())
            .packetType(sourceEnvelope.header().packetType())
            .payloadType(sourceEnvelope.header().payloadType())
            .ackPolicy(sourceEnvelope.header().ackPolicy())
            .priority(sourceEnvelope.header().priority())
            .threadPolicy(sourceEnvelope.header().threadPolicy())
            .deadline(sourceEnvelope.header().deadline())
            .expireAt(sourceEnvelope.header().expireAt())
            .cancellationScope(sourceEnvelope.header().cancellationScope())
            .failurePolicy(sourceEnvelope.header().failurePolicy())
            .payload(sourceEnvelope.payload())
            .build();
    }

    private void dispatchToRegistration(TianshuEnvelope envelope, HandlerRegistration registration) {
        ValidationResult validation = capabilityRegistry.validate(envelope, registration);
        if (!validation.accepted()) {
            deadLetterQueue.add(envelope, validation.code(), validation.message(), DeadLetterPolicy.LOG_ONLY);
            return;
        }
        String brokerTarget = envelope.header().packetType() == PacketType.RESPONSE
                ? registration.capabilityDescriptor().capabilityId()
                : envelope.header().target();
        ProtocolBroker broker = brokerRegistry.brokerFor(brokerTarget, registration.capabilityDescriptor().requiredBrokerType(), registration.moduleDescriptor().queueCapacity(), registration.moduleDescriptor().maxConcurrency());
        BrokerSubmitResult result = broker.submit(envelope, registration, this);
        if (result.rejected()) {
            deadLetterQueue.add(envelope, result.code(), result.message(), DeadLetterPolicy.LOG_ONLY);
        }
    }

    private List<HandlerRegistration> resolveHandlers(TianshuEnvelope envelope) {
        if (envelope.header().packetType() == PacketType.RESPONSE) {
            return responseHandlerRegistry.findResponse(envelope.parentId(), envelope.header().payloadType());
        }
        if (envelope.header().targetMode() == TargetMode.CAPABILITY) {
            return capabilityRegistry.findCapability(envelope.header().target());
        }
        if (envelope.header().targetMode() == TargetMode.TOPIC) {
            if (topicRegistry.find(envelope.header().target()).isEmpty()) return List.of();
            return topicSubscriptionRegistry.findTopic(envelope.header().target());
        }
        return List.of();
    }

    private void handleCancelEnvelope(TianshuEnvelope envelope) {
        if (!(envelope.payload() instanceof CancelPayload payload)) {
            deadLetterQueue.add(envelope, "INVALID_CANCEL_PAYLOAD", "Cancel envelope payload is invalid", DeadLetterPolicy.LOG_ONLY);
            return;
        }
        if (payload.targetEnvelopeId() == null || payload.targetEnvelopeId().isBlank()) {
            deadLetterQueue.add(envelope, "INVALID_CANCEL_TARGET", "Cancel target envelope id is blank", DeadLetterPolicy.LOG_ONLY);
            return;
        }
        if (lifecycleStore.findEnvelope(payload.targetEnvelopeId()).isEmpty()) {
            deadLetterQueue.add(envelope, "CANCEL_TARGET_NOT_FOUND", "Cancel target envelope does not exist", DeadLetterPolicy.LOG_ONLY);
            return;
        }
        lifecycleStore.findEnvelope(payload.targetEnvelopeId()).ifPresent(target -> cancel(target, payload.reasonCode(), payload.message()));
        lifecycleStore.transition(envelope.envelopeId(), EnvelopeStatus.COMPLETED, "CANCEL_DISPATCHED", "Cancel applied to " + payload.targetEnvelopeId());
    }

    public void handleFailure(TianshuEnvelope envelope, String reasonCode, String message, Throwable throwable) {
        lifecycleStore.transition(envelope.envelopeId(), EnvelopeStatus.FAILED, reasonCode, message == null ? "" : message);
        if (envelope.header().failurePolicy() == FailurePolicy.PROPAGATE_CANCEL) {
            applyCancellation(envelope, reasonCode, message == null ? "" : message);
        }
    }

    public void cancel(TianshuEnvelope envelope, String reasonCode, String message) {
        applyCancellation(envelope, reasonCode, message);
    }

    private void applyCancellation(TianshuEnvelope envelope, String reasonCode, String message) {
        CancellationScope scope = envelope.header().cancellationScope();
        if (scope == CancellationScope.SELF_ONLY) {
            cancellationRegistry.cancelSelf(envelope.envelopeId(), reasonCode, message);
            brokerRegistry.cancel(envelope.envelopeId(), reasonCode, message);
        } else if (scope == CancellationScope.CHILDREN) {
            cancellationRegistry.cancelChildren(envelope.envelopeId(), reasonCode, message);
            brokerRegistry.cancel(envelope.envelopeId(), reasonCode, message);
            for (TianshuEnvelope child : lifecycleStore.childrenOf(envelope.envelopeId())) {
                brokerRegistry.cancel(child.envelopeId(), reasonCode, message);
            }
        } else if (scope == CancellationScope.TRACE) {
            cancellationRegistry.cancelTrace(envelope.traceId(), reasonCode, message);
            for (TianshuEnvelope traceEnvelope : lifecycleStore.envelopesByTrace(envelope.traceId())) {
                brokerRegistry.cancel(traceEnvelope.envelopeId(), reasonCode, message);
            }
        } else if (scope == CancellationScope.RESOURCE) {
            cancellationRegistry.cancelSelf(envelope.envelopeId(), reasonCode, message);
            brokerRegistry.cancel(envelope.envelopeId(), reasonCode, message);
        }
    }

    private int traceDepth(TianshuEnvelope envelope) {
        int depth = 0;
        String parentId = envelope.parentId();
        while (parentId != null) {
            depth++;
            parentId = lifecycleStore.findEnvelope(parentId).map(TianshuEnvelope::parentId).orElse(null);
        }
        return depth;
    }

    public EnvelopeLifecycleStore lifecycle() { return lifecycleStore; }
    public CancellationRegistry cancellation() { return cancellationRegistry; }
    public DeadLetterQueue deadLetters() { return deadLetterQueue; }
    public StormGuard stormGuard() { return stormGuard; }
    public BrokerRegistry brokers() { return brokerRegistry; }
    public ModuleRegistry modules() { return moduleRegistry; }
    public CapabilityRegistry capabilities() { return capabilityRegistry; }
    public ResponseHandlerRegistry responseHandlers() { return responseHandlerRegistry; }
    public TopicRegistry topics() { return topicRegistry; }
    public TopicSubscriptionRegistry topicSubscriptions() { return topicSubscriptionRegistry; }
    public VoiceTriggerRegistry voiceTriggers() { return voiceTriggerRegistry; }
    public IntegrationModuleRegistry integrationModules() { return integrationModuleRegistry; }
    public ModuleStatusCache moduleStatusCache() { return moduleStatusCache; }
    public ProtocolExecutorManager executors() { return executorManager; }
    public ProtocolContext context() { return context; }
    public RuntimeInterruptPublisher runtimeInterrupts() { return this; }

    private void handleModuleStatus(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope.payload() instanceof ModuleStatusPayload payload) {
            moduleStatusCache.accept(payload.status());
        }
        context.complete(envelope.envelopeId());
    }

    private void subscribeModuleStatusCache() {
        subscribeTopic(
                new ModuleDescriptor(
                        "core.module_status_cache",
                        List.of(),
                        ThreadPolicy.ASYNC_WORKER,
                        CancellationScope.SELF_ONLY,
                        FailurePolicy.REPORT_ONLY,
                        DeliveryPolicy.LATEST_ONLY,
                        false,
                        false,
                        1,
                        32
                ),
                new TopicSubscriptionDescriptor(
                        ProtocolTopics.MODULE_STATUS,
                        PayloadType.MODULE_STATUS,
                        ModuleStatusPayload.class,
                        BrokerType.STATELESS_FAST_PATH,
                        EnumSet.of(PacketType.EVENT),
                        Priority.LOW,
                        CompletionPolicy.AUTO_COMPLETE_ON_RETURN
                ),
                this::handleModuleStatus
        );
    }

    @Override
    public void publishRuntimeInterrupt(long sessionId, RuntimeInterruptPayload.Reason reason, String detail) {
        submit(EnvelopeBuilder.eventTopic(
                        RUNTIME_SOURCE_ID,
                        ProtocolTopics.SYSTEM_RUNTIME_INTERRUPT,
                        PayloadType.CUSTOM,
                        new RuntimeInterruptPayload(sessionId, reason, detail == null ? "" : detail, System.currentTimeMillis())
                )
                .priority(Priority.CRITICAL)
                .build());
    }

    @Override
    public void close() {
        executorManager.close();
    }

    private final class RuntimeContext implements ProtocolContext {

        @Override
        public void submit(TianshuEnvelope envelope) {
            ProtocolRuntime.this.submit(envelope);
        }

        @Override
        public void complete(String envelopeId) {
            lifecycleStore.transition(envelopeId, EnvelopeStatus.COMPLETED, "COMPLETED", "");
        }

        @Override
        public void fail(String envelopeId, String reasonCode, String message, Throwable throwable) {
            lifecycleStore.findEnvelope(envelopeId).ifPresent(envelope -> handleFailure(envelope, reasonCode, message, throwable));
        }

        @Override
        public void cancel(String envelopeId, String reasonCode, String message) {
            lifecycleStore.findEnvelope(envelopeId).ifPresent(envelope -> ProtocolRuntime.this.cancel(envelope, reasonCode, message));
        }

        @Override
        public boolean isCancelled(String envelopeId) {
            return cancellationRegistry.isCancelled(envelopeId);
        }

        @Override
        public void onCancel(String envelopeId, Consumer<TianshuEnvelope> callback) {
            cancellationRegistry.onCancel(envelopeId, callback);
        }
    }
}

