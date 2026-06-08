package com.rheinmetal.tianshu.protocol.observability;

import com.rheinmetal.tianshu.protocol.broker.BrokerSnapshot;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.DeadLetterRecord;
import com.rheinmetal.tianshu.protocol.runtime.EnvelopeTransition;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistration;

import java.util.List;
import java.util.Map;

public record ProtocolRuntimeSnapshot(List<BrokerSnapshot> brokers, List<ModuleDescriptor> modules, List<TopicDescriptor> topics, List<String> topicSubscriptionIds, List<String> responseRequestIds, List<String> capabilityIds, List<VoiceTriggerRegistration> voiceTriggers, List<DeadLetterRecord> deadLetters, List<EnvelopeTransition> transitions, Map<String, Integer> stormRejects) {
    public static ProtocolRuntimeSnapshot from(ProtocolRuntime runtime) {
        return new ProtocolRuntimeSnapshot(runtime.brokers().snapshots(), runtime.modules().snapshot(), runtime.topics().snapshot(), runtime.topicSubscriptions().topicIds(), runtime.responseHandlers().requestEnvelopeIds(), runtime.capabilities().capabilityIds(), runtime.voiceTriggers().registrations(), runtime.deadLetters().snapshot(64), runtime.lifecycle().allTransitions(), runtime.stormGuard().rejectionSnapshot());
    }
}
