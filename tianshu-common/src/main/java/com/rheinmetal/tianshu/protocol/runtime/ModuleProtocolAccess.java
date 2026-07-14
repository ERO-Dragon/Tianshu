package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerAccess;

public interface ModuleProtocolAccess {
    void registerModule(ModuleDescriptor descriptor, EnvelopeHandler handler);

    void registerTopic(TopicDescriptor descriptor);

    void registerResponseHandler(String requestEnvelopeId, ModuleDescriptor descriptor, CapabilityDescriptor capabilityDescriptor, EnvelopeHandler handler);

    void unregisterResponseHandlers(String requestEnvelopeId);

    void subscribeTopic(ModuleDescriptor moduleDescriptor, TopicSubscriptionDescriptor descriptor, EnvelopeHandler handler);

    void submit(TianshuEnvelope envelope);

    VoiceTriggerAccess voiceTriggers();
}
