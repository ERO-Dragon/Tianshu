package com.rheinmetal.tianshu.protocol.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TopicSubscriptionRegistry {
    private final Map<String, List<HandlerRegistration>> subscriptions = new ConcurrentHashMap<>();

    public void subscribe(ModuleDescriptor moduleDescriptor, TopicSubscriptionDescriptor descriptor, EnvelopeHandler handler) {
        HandlerRegistration registration = new HandlerRegistration(moduleDescriptor, descriptor.asCapabilityDescriptor(), handler);
        subscriptions.compute(descriptor.topicId(), (key, existing) -> {
            List<HandlerRegistration> result = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            result.add(registration);
            return List.copyOf(result);
        });
    }

    public List<HandlerRegistration> findTopic(String topicId) {
        return subscriptions.getOrDefault(topicId, List.of());
    }

    public List<String> topicIds() {
        return new ArrayList<>(subscriptions.keySet());
    }
}
