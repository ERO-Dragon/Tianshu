package com.rheinmetal.tianshu.function.ia.registry;

import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class DialogueParticipantRegistry {
    private final Map<String, DialogueParticipantDescriptor> participants = new ConcurrentHashMap<>();

    public void register(DialogueParticipantDescriptor descriptor) {
        participants.put(key(descriptor.moduleId(), descriptor.participantId()), descriptor);
    }

    public Optional<DialogueParticipantDescriptor> find(String moduleId, String participantId) {
        return Optional.ofNullable(participants.get(key(moduleId, participantId)));
    }

    public List<DialogueParticipantDescriptor> snapshot() {
        return participants.values().stream()
                .sorted((left, right) -> key(left.moduleId(), left.participantId()).compareTo(key(right.moduleId(), right.participantId())))
                .toList();
    }

    public Optional<DialogueParticipantDescriptor> unregister(String moduleId, String participantId) {
        return Optional.ofNullable(participants.remove(key(moduleId, participantId)));
    }

    public List<DialogueParticipantDescriptor> unregisterModule(String moduleId) {
        String normalized = sanitize(moduleId);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<DialogueParticipantDescriptor> removed = participants.values().stream()
                .filter(descriptor -> descriptor.moduleId().equals(normalized))
                .toList();
        participants.entrySet().removeIf(entry -> entry.getValue().moduleId().equals(normalized));
        return removed;
    }

    public void clear() {
        participants.clear();
    }

    private static String key(String moduleId, String participantId) {
        return sanitize(moduleId) + ":" + sanitize(participantId);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
