package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistry;

public interface ModuleProtocolAccess {
    void registerModule(ModuleDescriptor descriptor, EnvelopeHandler handler);

    void registerTopic(TopicDescriptor descriptor);

    void registerDirectRoute(String routeId, ModuleDescriptor descriptor, CapabilityDescriptor capabilityDescriptor, EnvelopeHandler handler);

    void subscribeTopic(ModuleDescriptor moduleDescriptor, TopicSubscriptionDescriptor descriptor, EnvelopeHandler handler);

    void submit(TianshuEnvelope envelope);

    ProtocolTaskHandle submitTask(ProtocolTaskSpec spec, Runnable task);

    VoiceTriggerRegistry voiceTriggers();
}
