package com.rheinmetal.tianshu.function.ia.session;

import com.rheinmetal.tianshu.function.ia.model.DialogueAttentionState;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaim;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class DialogueAttentionMemory {
    private final ConcurrentMap<String, DialogueAttentionState> states = new ConcurrentHashMap<>();

    public void remember(String playerId, DialogueParticipantDescriptor owner, DialogueClaim claim, long nowMillis) {
        if (owner == null || claim == null) {
            clearPlayer(playerId);
            return;
        }
        states.put(requireText(playerId, "playerId"), new DialogueAttentionState(
                playerId,
                owner.moduleId(),
                owner.participantId(),
                claim.attention(),
                claim.decay(),
                nowMillis
        ));
    }

    public Optional<DialogueAttentionState> activeForPlayer(String playerId, List<DialogueParticipantDescriptor> participants, long nowMillis) {
        String key = sanitize(playerId);
        if (key.isBlank()) {
            return Optional.empty();
        }
        DialogueAttentionState state = states.get(key);
        if (state == null) {
            return Optional.empty();
        }
        if (!state.beatsAxAt(nowMillis) || !participantExists(state, participants)) {
            states.remove(key, state);
            return Optional.empty();
        }
        return Optional.of(state);
    }

    public List<String> playerIds() {
        return List.copyOf(states.keySet());
    }

    public void clearPlayer(String playerId) {
        String key = sanitize(playerId);
        if (!key.isBlank()) {
            states.remove(key);
        }
    }

    public void clear() {
        states.clear();
    }

    private static boolean participantExists(DialogueAttentionState state, List<DialogueParticipantDescriptor> participants) {
        return participants != null && participants.stream()
                .anyMatch(participant -> participant != null && state.ownedBy(participant.moduleId(), participant.participantId()));
    }

    private static String requireText(String value, String name) {
        String normalized = sanitize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return normalized;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
