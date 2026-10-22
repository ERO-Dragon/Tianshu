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
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.voice.VoiceCommandCategory;
import com.rheinmetal.tianshu.protocol.voice.VoiceCommandScope;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistration;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;

public abstract class AbstractProtocolAdapter {
    private final String moduleId;
    private final String sourceId;
    private final ModuleRuntimeAccess runtime;
    private final AdapterDefaults defaults;

    protected AbstractProtocolAdapter(String moduleId, String sourceId, ModuleRuntimeAccess runtime, AdapterDefaults defaults) {
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

    protected final ModuleRuntimeAccess runtime() {
        return runtime;
    }

    protected final AdapterDefaults defaults() {
        return defaults;
    }

    protected final void registerVoiceTrigger(List<String> wakeWords, List<String> extraWords) {
        registerVoiceTrigger(wakeWords, extraWords, VoiceCommandCategory.GENERAL, VoiceCommandScope.CLIENT, false);
    }

    protected final void registerVoiceTrigger(List<String> wakeWords, List<String> commandWords, VoiceCommandCategory category, VoiceCommandScope scope, boolean dialogueEligible) {
        runtime.voiceTriggers().register(new VoiceTriggerRegistration(moduleId, wakeWords, commandWords, category, scope, dialogueEligible));
    }

    protected final ProtocolTaskHandle submitTask(ExecutionLane lane, Runnable task) {
        return submitTask(taskSpec(lane).build(), task);
    }

    protected final ProtocolTaskHandle submitTask(ProtocolTaskSpec spec, Runnable task) {
        return runtime.submit(spec, task);
    }

    protected final <T> ProtocolTaskHandle submitTask(ProtocolTaskSpec spec, Callable<T> task) {
        return runtime.submit(spec, task);
    }

    protected final ProtocolTaskSpec.Builder taskSpec(ExecutionLane lane) {
        return ProtocolTaskSpec.builder()
                .moduleId(moduleId)
                .lane(lane);
    }

    protected final void registerCapability(String capabilityId, PayloadType payloadType, Class<? extends ITianshuPayload> payloadClass, BrokerType brokerType, Set<PacketType> acceptedPacketTypes, Priority minPriority, EnvelopeHandler handler) {
        registerCapability(capabilityId, payloadType, payloadClass, brokerType, acceptedPacketTypes, minPriority, CompletionPolicy.AUTO_COMPLETE_ON_RETURN, handler, defaults);
    }

    protected final void registerCapability(String capabilityId, PayloadType payloadType, Class<? extends ITianshuPayload> payloadClass, BrokerType brokerType, Set<PacketType> acceptedPacketTypes, Priority minPriority, CompletionPolicy completionPolicy, EnvelopeHandler handler, AdapterDefaults options) {
        AdapterDefaults effective = options(options);
        CapabilityDescriptor capability = capabilityDescriptor(capabilityId, payloadType, payloadClass, brokerType, acceptedPacketTypes, minPriority, completionPolicy);
        runtime.registerModule(moduleDescriptor(List.of(capability), effective), Objects.requireNonNull(handler, "handler"));
    }

    protected final void registerResponseHandler(String requestEnvelopeId, PayloadType payloadType, Class<? extends ITianshuPayload> payloadClass, BrokerType brokerType, Set<PacketType> acceptedPacketTypes, Priority minPriority, EnvelopeHandler handler) {
        registerResponseHandler(requestEnvelopeId, payloadType, payloadClass, brokerType, acceptedPacketTypes, minPriority, CompletionPolicy.AUTO_COMPLETE_ON_RETURN, handler, defaults);
    }

    protected final void registerResponseHandler(String requestEnvelopeId, PayloadType payloadType, Class<? extends ITianshuPayload> payloadClass, BrokerType brokerType, Set<PacketType> acceptedPacketTypes, Priority minPriority, CompletionPolicy completionPolicy, EnvelopeHandler handler, AdapterDefaults options) {
        AdapterDefaults effective = options(options);
        CapabilityDescriptor capability = capabilityDescriptor("response:" + requireText(requestEnvelopeId, "requestEnvelopeId") + ":" + payloadType, payloadType, payloadClass, brokerType, acceptedPacketTypes, minPriority, completionPolicy);
        runtime.registerResponseHandler(requestEnvelopeId, moduleDescriptor(List.of(), effective), capability, Objects.requireNonNull(handler, "handler"));
    }

    protected final void unregisterResponseHandlers(String requestEnvelopeId) {
        runtime.unregisterResponseHandlers(requestEnvelopeId);
    }

    protected final void subscribeTopic(String topicId, PayloadType payloadType, Class<? extends ITianshuPayload> payloadClass, BrokerType brokerType, Set<PacketType> acceptedPacketTypes, Priority minPriority, EnvelopeHandler handler) {
        subscribeTopic(topicId, payloadType, payloadClass, brokerType, acceptedPacketTypes, minPriority, CompletionPolicy.AUTO_COMPLETE_ON_RETURN, handler, defaults);
    }

    protected final void subscribeTopic(String topicId, PayloadType payloadType, Class<? extends ITianshuPayload> payloadClass, BrokerType brokerType, Set<PacketType> acceptedPacketTypes, Priority minPriority, CompletionPolicy completionPolicy, EnvelopeHandler handler, AdapterDefaults options) {
        AdapterDefaults effective = options(options);
        TopicSubscriptionDescriptor subscription = new TopicSubscriptionDescriptor(topicId, payloadType, payloadClass, brokerType, packetTypes(acceptedPacketTypes), minPriority, completionPolicy);
        runtime.subscribeTopic(moduleDescriptor(List.of(), effective), subscription, Objects.requireNonNull(handler, "handler"));
    }

    protected final TianshuEnvelope publishTopic(String topicId, PayloadType payloadType, ITianshuPayload payload) {
        return publishTopic(topicId, payloadType, payload, defaults);
    }

    protected final TianshuEnvelope publishTopic(String topicId, PayloadType payloadType, ITianshuPayload payload, AdapterDefaults options) {
        return submitToTopic(topicId, PacketType.EVENT, payloadType, payload, options);
    }

    protected final TianshuEnvelope publishTopic(TianshuEnvelope parent, String topicId, PayloadType payloadType, ITianshuPayload payload) {
        return publishTopic(parent, topicId, payloadType, payload, defaults);
    }

    protected final TianshuEnvelope publishTopic(TianshuEnvelope parent, String topicId, PayloadType payloadType, ITianshuPayload payload, AdapterDefaults options) {
        return submitToTopic(parent, topicId, PacketType.EVENT, payloadType, payload, options);
    }

    protected final TianshuEnvelope submitToTopic(String topicId, PacketType packetType, PayloadType payloadType, ITianshuPayload payload) {
        return submitToTopic(topicId, packetType, payloadType, payload, defaults);
    }

    protected final TianshuEnvelope submitToTopic(String topicId, PacketType packetType, PayloadType payloadType, ITianshuPayload payload, AdapterDefaults options) {
        return submit(EnvelopeBuilder.eventTopic(sourceId, topicId, payloadType, payload).packetType(packetType), options);
    }

    protected final TianshuEnvelope submitToTopic(TianshuEnvelope parent, String topicId, PacketType packetType, PayloadType payloadType, ITianshuPayload payload) {
        return submitToTopic(parent, topicId, packetType, payloadType, payload, defaults);
    }

    protected final TianshuEnvelope submitToTopic(TianshuEnvelope parent, String topicId, PacketType packetType, PayloadType payloadType, ITianshuPayload payload, AdapterDefaults options) {
        Objects.requireNonNull(parent, "parent");
        return submit(EnvelopeBuilder.childOf(parent)
                .sourceId(sourceId)
                .targetMode(TargetMode.TOPIC)
                .target(topicId)
                .packetType(packetType)
                .payloadType(payloadType)
                .payload(payload), options);
    }

    protected final TianshuEnvelope requestCapability(String capabilityId, PayloadType payloadType, ITianshuPayload payload) {
        return requestCapability(capabilityId, payloadType, payload, defaults);
    }

    protected final TianshuEnvelope requestCapability(String capabilityId, PayloadType payloadType, ITianshuPayload payload, AdapterDefaults options) {
        return submitToCapability(capabilityId, PacketType.REQUEST, payloadType, payload, AckPolicy.EXPECT_SUCCESS_OR_FAILURE, options);
    }

    protected final TianshuEnvelope requestCapability(TianshuEnvelope parent, String capabilityId, PayloadType payloadType, ITianshuPayload payload) {
        return requestCapability(parent, capabilityId, payloadType, payload, defaults);
    }

    protected final TianshuEnvelope requestCapability(TianshuEnvelope parent, String capabilityId, PayloadType payloadType, ITianshuPayload payload, AdapterDefaults options) {
        return submitToCapability(parent, capabilityId, PacketType.REQUEST, payloadType, payload, AckPolicy.EXPECT_SUCCESS_OR_FAILURE, options);
    }

    protected final TianshuEnvelope commandCapability(String capabilityId, PayloadType payloadType, ITianshuPayload payload) {
        return commandCapability(capabilityId, payloadType, payload, defaults);
    }

    protected final TianshuEnvelope commandCapability(String capabilityId, PayloadType payloadType, ITianshuPayload payload, AdapterDefaults options) {
        return submitToCapability(capabilityId, PacketType.COMMAND, payloadType, payload, AckPolicy.NONE, options);
    }

    protected final TianshuEnvelope commandCapability(TianshuEnvelope parent, String capabilityId, PayloadType payloadType, ITianshuPayload payload) {
        return commandCapability(parent, capabilityId, payloadType, payload, defaults);
    }

    protected final TianshuEnvelope commandCapability(TianshuEnvelope parent, String capabilityId, PayloadType payloadType, ITianshuPayload payload, AdapterDefaults options) {
        return submitToCapability(parent, capabilityId, PacketType.COMMAND, payloadType, payload, AckPolicy.NONE, options);
    }

    protected final TianshuEnvelope submitToCapability(String capabilityId, PacketType packetType, PayloadType payloadType, ITianshuPayload payload) {
        return submitToCapability(capabilityId, packetType, payloadType, payload, AckPolicy.NONE, defaults);
    }

    protected final TianshuEnvelope submitToCapability(String capabilityId, PacketType packetType, PayloadType payloadType, ITianshuPayload payload, AdapterDefaults options) {
        return submitToCapability(capabilityId, packetType, payloadType, payload, AckPolicy.NONE, options);
    }

    protected final TianshuEnvelope submitToCapability(String capabilityId, PacketType packetType, PayloadType payloadType, ITianshuPayload payload, AckPolicy ackPolicy, AdapterDefaults options) {
        return submit(EnvelopeBuilder.commandToCapability(sourceId, capabilityId, payloadType, payload)
                .packetType(packetType)
                .ackPolicy(ackPolicy), options);
    }

    protected final TianshuEnvelope submitToCapability(TianshuEnvelope parent, String capabilityId, PacketType packetType, PayloadType payloadType, ITianshuPayload payload) {
        return submitToCapability(parent, capabilityId, packetType, payloadType, payload, AckPolicy.NONE, defaults);
    }

    protected final TianshuEnvelope submitToCapability(TianshuEnvelope parent, String capabilityId, PacketType packetType, PayloadType payloadType, ITianshuPayload payload, AdapterDefaults options) {
        return submitToCapability(parent, capabilityId, packetType, payloadType, payload, AckPolicy.NONE, options);
    }

    protected final TianshuEnvelope submitToCapability(TianshuEnvelope parent, String capabilityId, PacketType packetType, PayloadType payloadType, ITianshuPayload payload, AckPolicy ackPolicy, AdapterDefaults options) {
        Objects.requireNonNull(parent, "parent");
        return submit(EnvelopeBuilder.childOf(parent)
                .sourceId(sourceId)
                .targetMode(TargetMode.CAPABILITY)
                .target(capabilityId)
                .packetType(packetType)
                .payloadType(payloadType)
                .ackPolicy(ackPolicy)
                .payload(payload), options);
    }

    protected final TianshuEnvelope respondTo(TianshuEnvelope parent, PayloadType payloadType, ITianshuPayload payload) {
        return respondTo(parent, payloadType, payload, defaults);
    }

    protected final TianshuEnvelope respondTo(TianshuEnvelope parent, PayloadType payloadType, ITianshuPayload payload, AdapterDefaults options) {
        Objects.requireNonNull(parent, "parent");
        return submit(EnvelopeBuilder.responseTo(sourceId, parent, payloadType, payload), options);
    }

    protected final TianshuEnvelope cancelEnvelope(TianshuEnvelope targetEnvelope, String reasonCode, String message) {
        return cancelEnvelope(targetEnvelope, reasonCode, message, defaults);
    }

    protected final TianshuEnvelope cancelEnvelope(TianshuEnvelope targetEnvelope, String reasonCode, String message, AdapterDefaults options) {
        Objects.requireNonNull(targetEnvelope, "targetEnvelope");
        return submit(EnvelopeBuilder.cancelEnvelope(sourceId, targetEnvelope, reasonCode, message), options);
    }

    protected final TianshuEnvelope buildRequestCapability(String capabilityId, PayloadType payloadType, ITianshuPayload payload) {
        return build(EnvelopeBuilder.requestCapability(sourceId, capabilityId, payloadType, payload), defaults);
    }

    protected final TianshuEnvelope buildRequestCapability(TianshuEnvelope parent, String capabilityId, PayloadType payloadType, ITianshuPayload payload) {
        Objects.requireNonNull(parent, "parent");
        return build(EnvelopeBuilder.childOf(parent)
                .sourceId(sourceId)
                .targetMode(TargetMode.CAPABILITY)
                .target(capabilityId)
                .packetType(PacketType.REQUEST)
                .payloadType(payloadType)
                .ackPolicy(AckPolicy.EXPECT_SUCCESS_OR_FAILURE)
                .payload(payload), defaults);
    }

    protected final TianshuEnvelope submitPrepared(TianshuEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        runtime.submit(envelope);
        return envelope;
    }

    protected final TianshuEnvelope submit(EnvelopeBuilder builder, AdapterDefaults options) {
        TianshuEnvelope envelope = build(builder, options);
        runtime.submit(envelope);
        return envelope;
    }

    protected final TianshuEnvelope build(EnvelopeBuilder builder, AdapterDefaults options) {
        Objects.requireNonNull(builder, "builder");
        AdapterDefaults effective = options(options);
        long now = System.currentTimeMillis();
        return builder
                .sourceId(sourceId)
                .priority(effective.priority())
                .threadPolicy(effective.threadPolicy())
                .deliveryPolicy(effective.deliveryPolicy())
                .cancellationScope(effective.cancellationScope())
                .failurePolicy(effective.failurePolicy())
                .deadline(now + effective.deadlineMs())
                .expireAt(now + effective.expireMs())
                .build();
    }

    private CapabilityDescriptor capabilityDescriptor(String capabilityId, PayloadType payloadType, Class<? extends ITianshuPayload> payloadClass, BrokerType brokerType, Set<PacketType> acceptedPacketTypes, Priority minPriority, CompletionPolicy completionPolicy) {
        return new CapabilityDescriptor(capabilityId, payloadType, payloadClass, brokerType, packetTypes(acceptedPacketTypes), minPriority, completionPolicy);
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
