package com.rheinmetal.tianshu.protocol.adapter;

import com.rheinmetal.tianshu.protocol.AckPolicy;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TargetMode;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public abstract class AbstractProtocolAdapter {
    private final String moduleId;
    private final String sourceId;
    private final ProtocolRuntime runtime;
    private final AdapterDefaults defaults;

    protected AbstractProtocolAdapter(String moduleId, String sourceId, ProtocolRuntime runtime, AdapterDefaults defaults) {
        this.moduleId = requireText(moduleId, "moduleId");
        this.sourceId = requireText(sourceId, "sourceId");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.defaults = defaults == null ? AdapterDefaults.standard() : defaults;
    }

    public final String moduleId() {
        return moduleId;
    }

    public final String sourceId() {
        return sourceId;
    }

    protected final ProtocolRuntime runtime() {
        return runtime;
    }

    protected final AdapterDefaults defaults() {
        return defaults;
    }

    protected final void registerCapability(String capabilityId, PayloadType payloadType, Class<? extends ITianshuPayload> payloadClass, BrokerType brokerType, Set<PacketType> acceptedPacketTypes, Priority minPriority, EnvelopeHandler handler) {
        registerCapability(capabilityId, payloadType, payloadClass, brokerType, acceptedPacketTypes, minPriority, CompletionPolicy.AUTO_COMPLETE_ON_RETURN, handler, defaults);
    }

    protected final void registerCapability(String capabilityId, PayloadType payloadType, Class<? extends ITianshuPayload> payloadClass, BrokerType brokerType, Set<PacketType> acceptedPacketTypes, Priority minPriority, CompletionPolicy completionPolicy, EnvelopeHandler handler, AdapterDefaults options) {
        AdapterDefaults effective = options(options);
        CapabilityDescriptor capability = new CapabilityDescriptor(capabilityId, payloadType, payloadClass, brokerType, packetTypes(acceptedPacketTypes), minPriority, completionPolicy);
        runtime.registerModule(moduleDescriptor(List.of(capability), effective), handler);
    }

    protected final void subscribeTopic(String topicId, PayloadType payloadType, Class<? extends ITianshuPayload> payloadClass, BrokerType brokerType, Set<PacketType> acceptedPacketTypes, Priority minPriority, EnvelopeHandler handler) {
        subscribeTopic(topicId, payloadType, payloadClass, brokerType, acceptedPacketTypes, minPriority, CompletionPolicy.AUTO_COMPLETE_ON_RETURN, handler, defaults);
    }

    protected final void subscribeTopic(String topicId, PayloadType payloadType, Class<? extends ITianshuPayload> payloadClass, BrokerType brokerType, Set<PacketType> acceptedPacketTypes, Priority minPriority, CompletionPolicy completionPolicy, EnvelopeHandler handler, AdapterDefaults options) {
        AdapterDefaults effective = options(options);
        TopicSubscriptionDescriptor subscription = new TopicSubscriptionDescriptor(topicId, payloadType, payloadClass, brokerType, packetTypes(acceptedPacketTypes), minPriority, completionPolicy);
        runtime.subscribeTopic(moduleDescriptor(List.of(), effective), subscription, handler);
    }

    protected final TianshuEnvelope publishTopic(String topicId, PayloadType payloadType, ITianshuPayload payload) {
        return publishTopic(topicId, payloadType, payload, defaults);
    }

    protected final TianshuEnvelope publishTopic(String topicId, PayloadType payloadType, ITianshuPayload payload, AdapterDefaults options) {
        return submit(EnvelopeBuilder.eventTopic(sourceId, topicId, payloadType, payload), options);
    }

    protected final TianshuEnvelope publishTopic(TianshuEnvelope parent, String topicId, PayloadType payloadType, ITianshuPayload payload) {
        return publishTopic(parent, topicId, payloadType, payload, defaults);
    }

    protected final TianshuEnvelope publishTopic(TianshuEnvelope parent, String topicId, PayloadType payloadType, ITianshuPayload payload, AdapterDefaults options) {
        Objects.requireNonNull(parent, "parent");
        return submit(EnvelopeBuilder.childOf(parent)
                .sourceId(sourceId)
                .targetMode(TargetMode.TOPIC)
                .target(topicId)
                .packetType(PacketType.EVENT)
                .payloadType(payloadType)
                .payload(payload), options);
    }

    protected final TianshuEnvelope requestCapability(String capabilityId, PayloadType payloadType, ITianshuPayload payload) {
        return requestCapability(capabilityId, payloadType, payload, defaults);
    }

    protected final TianshuEnvelope requestCapability(String capabilityId, PayloadType payloadType, ITianshuPayload payload, AdapterDefaults options) {
        return submit(EnvelopeBuilder.requestCapability(sourceId, capabilityId, payloadType, payload), options);
    }

    protected final TianshuEnvelope requestCapability(TianshuEnvelope parent, String capabilityId, PayloadType payloadType, ITianshuPayload payload) {
        return requestCapability(parent, capabilityId, payloadType, payload, defaults);
    }

    protected final TianshuEnvelope requestCapability(TianshuEnvelope parent, String capabilityId, PayloadType payloadType, ITianshuPayload payload, AdapterDefaults options) {
        Objects.requireNonNull(parent, "parent");
        return submit(EnvelopeBuilder.childOf(parent)
                .sourceId(sourceId)
                .targetMode(TargetMode.CAPABILITY)
                .target(capabilityId)
                .packetType(PacketType.REQUEST)
                .payloadType(payloadType)
                .ackPolicy(AckPolicy.EXPECT_SUCCESS_OR_FAILURE)
                .payload(payload), options);
    }

    protected final TianshuEnvelope commandCapability(String capabilityId, PayloadType payloadType, ITianshuPayload payload) {
        return commandCapability(capabilityId, payloadType, payload, defaults);
    }

    protected final TianshuEnvelope commandCapability(String capabilityId, PayloadType payloadType, ITianshuPayload payload, AdapterDefaults options) {
        return submit(EnvelopeBuilder.commandToCapability(sourceId, capabilityId, payloadType, payload), options);
    }

    protected final TianshuEnvelope commandCapability(TianshuEnvelope parent, String capabilityId, PayloadType payloadType, ITianshuPayload payload) {
        return commandCapability(parent, capabilityId, payloadType, payload, defaults);
    }

    protected final TianshuEnvelope commandCapability(TianshuEnvelope parent, String capabilityId, PayloadType payloadType, ITianshuPayload payload, AdapterDefaults options) {
        Objects.requireNonNull(parent, "parent");
        return submit(EnvelopeBuilder.childOf(parent)
                .sourceId(sourceId)
                .targetMode(TargetMode.CAPABILITY)
                .target(capabilityId)
                .packetType(PacketType.COMMAND)
                .payloadType(payloadType)
                .payload(payload), options);
    }

    protected final TianshuEnvelope submit(EnvelopeBuilder builder, AdapterDefaults options) {
        AdapterDefaults effective = options(options);
        long now = System.currentTimeMillis();
        TianshuEnvelope envelope = builder
                .sourceId(sourceId)
                .priority(effective.priority())
                .threadPolicy(effective.threadPolicy())
                .deliveryPolicy(effective.deliveryPolicy())
                .cancellationScope(effective.cancellationScope())
                .failurePolicy(effective.failurePolicy())
                .deadline(now + effective.deadlineMs())
                .expireAt(now + effective.expireMs())
                .build();
        runtime.submit(envelope);
        return envelope;
    }

    private ModuleDescriptor moduleDescriptor(List<CapabilityDescriptor> capabilities, AdapterDefaults options) {
        return new ModuleDescriptor(moduleId, capabilities, options.threadPolicy(), options.cancellationScope(), options.failurePolicy(), options.deliveryPolicy(), options.cancellable(), options.supportsStreaming(), options.maxConcurrency(), options.queueCapacity());
    }

    private AdapterDefaults options(AdapterDefaults value) {
        return value == null ? defaults : value;
    }

    private static Set<PacketType> packetTypes(Set<PacketType> value) {
        return value == null || value.isEmpty() ? EnumSet.allOf(PacketType.class) : value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}
