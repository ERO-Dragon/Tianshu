package com.rheinmetal.tianshu.protocol.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class TopicRegistry {
    private final Map<String, TopicDescriptor> topics = new ConcurrentHashMap<>();

    public void register(TopicDescriptor descriptor) {
        TopicDescriptor existing = topics.putIfAbsent(descriptor.topicId(), descriptor);
        if (existing != null && !existing.equals(descriptor)) {
            throw new IllegalStateException("Topic already registered with a different descriptor: " + descriptor.topicId());
        }
    }

    public Optional<TopicDescriptor> find(String topicId) {
        return Optional.ofNullable(topics.get(topicId));
    }

    public List<TopicDescriptor> snapshot() {
        return new ArrayList<>(topics.values());
    }
}
