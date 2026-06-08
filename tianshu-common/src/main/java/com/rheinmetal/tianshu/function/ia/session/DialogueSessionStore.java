package com.rheinmetal.tianshu.function.ia.session;

import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.function.ia.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.function.ia.model.DialogueSession;
import com.rheinmetal.tianshu.function.ia.model.DialogueSessionState;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class DialogueSessionStore {
    private final ConcurrentMap<String, DialogueSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> activeSessionByPlayer = new ConcurrentHashMap<>();

    public DialogueSession createClaimed(String playerId, String turnId, DialogueParticipantDescriptor owner, long nowMillis) {
        String sessionId = UUID.randomUUID().toString();
        DialogueSession pending = new DialogueSession(sessionId, playerId, "", "", DialogueSessionState.PENDING, turnId, nowMillis, nowMillis, nowMillis, null);
        DialogueSession claimed = pending.claim(owner, turnId, nowMillis);
        sessions.put(sessionId, claimed);
        activeSessionByPlayer.put(playerId, sessionId);
        return claimed;
    }

    public Optional<DialogueSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sanitize(sessionId)));
    }

    public Optional<DialogueSession> activeForPlayer(String playerId, long nowMillis) {
        String sessionId = activeSessionByPlayer.get(sanitize(playerId));
        if (sessionId == null) {
            return Optional.empty();
        }
        DialogueSession session = sessions.get(sessionId);
        if (session == null || !session.activeAt(nowMillis)) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public DialogueSession activate(String sessionId, long nowMillis) {
        return update(sessionId, session -> session.activate(nowMillis));
    }

    public DialogueSession extendProcessing(String sessionId, long nowMillis, long processingDeadlineMillis) {
        return update(sessionId, session -> session.extendProcessing(nowMillis, processingDeadlineMillis));
    }

    public DialogueSession interrupting(String sessionId, long nowMillis) {
        return update(sessionId, session -> session.interrupting(nowMillis));
    }

    public DialogueSession release(String sessionId, DialogueReleaseReason reason, long nowMillis) {
        DialogueSession released = update(sessionId, session -> session.terminal(DialogueSessionState.RELEASED, reason, nowMillis));
        activeSessionByPlayer.remove(released.playerId(), released.sessionId());
        return released;
    }

    public Optional<DialogueSession> releaseIfPresent(String sessionId, DialogueReleaseReason reason, long nowMillis) {
        return updateIfPresent(sessionId, session -> session.terminal(DialogueSessionState.RELEASED, reason, nowMillis))
                .map(released -> {
                    activeSessionByPlayer.remove(released.playerId(), released.sessionId());
                    return released;
                });
    }

    public DialogueSession expire(String sessionId, long nowMillis) {
        DialogueSession expired = update(sessionId, session -> session.terminal(DialogueSessionState.EXPIRED, DialogueReleaseReason.EXPIRED, nowMillis));
        activeSessionByPlayer.remove(expired.playerId(), expired.sessionId());
        return expired;
    }

    public Optional<DialogueSession> expireIfPresent(String sessionId, long nowMillis) {
        return updateIfPresent(sessionId, session -> session.terminal(DialogueSessionState.EXPIRED, DialogueReleaseReason.EXPIRED, nowMillis))
                .map(expired -> {
                    activeSessionByPlayer.remove(expired.playerId(), expired.sessionId());
                    return expired;
                });
    }

    public List<DialogueSession> releaseByOwnerModule(String moduleId, DialogueReleaseReason reason, long nowMillis) {
        String normalized = sanitize(moduleId);
        if (normalized.isBlank()) {
            return List.of();
        }
        return sessions.values().stream()
                .filter(session -> session.ownerModuleId().equals(normalized) && activeState(session.state()))
                .map(session -> releaseIfPresent(session.sessionId(), reason, nowMillis))
                .flatMap(Optional::stream)
                .toList();
    }

    public List<DialogueSession> releaseByOwnerParticipant(String moduleId, String participantId, DialogueReleaseReason reason, long nowMillis) {
        String normalizedModule = sanitize(moduleId);
        String normalizedParticipant = sanitize(participantId);
        if (normalizedModule.isBlank() || normalizedParticipant.isBlank()) {
            return List.of();
        }
        return sessions.values().stream()
                .filter(session -> session.ownedBy(normalizedModule, normalizedParticipant) && activeState(session.state()))
                .map(session -> releaseIfPresent(session.sessionId(), reason, nowMillis))
                .flatMap(Optional::stream)
                .toList();
    }

    public DialogueSession reject(String playerId, String turnId, long nowMillis) {
        String sessionId = UUID.randomUUID().toString();
        DialogueSession rejected = new DialogueSession(sessionId, playerId, "", "", DialogueSessionState.REJECTED, turnId, nowMillis, nowMillis, nowMillis, DialogueReleaseReason.REJECTED);
        sessions.put(sessionId, rejected);
        return rejected;
    }

    public List<DialogueSession> snapshot() {
        return sessions.values().stream()
                .sorted((left, right) -> left.sessionId().compareTo(right.sessionId()))
                .toList();
    }

    public List<DialogueSession> expireOverdue(long nowMillis) {
        return sessions.values().stream()
                .filter(session -> activeState(session.state()) && session.processingDeadlineMillis() <= nowMillis)
                .map(session -> expireIfPresent(session.sessionId(), nowMillis))
                .flatMap(Optional::stream)
                .toList();
    }

    public void clear() {
        sessions.clear();
        activeSessionByPlayer.clear();
    }

    private Optional<DialogueSession> updateIfPresent(String sessionId, java.util.function.Function<DialogueSession, DialogueSession> updater) {
        String normalized = sanitize(sessionId);
        DialogueSession existing = sessions.get(normalized);
        if (existing == null) {
            return Optional.empty();
        }
        DialogueSession updated = updater.apply(existing);
        sessions.put(normalized, updated);
        return Optional.of(updated);
    }

    private static boolean activeState(DialogueSessionState state) {
        return state != null
                && state != DialogueSessionState.RELEASED
                && state != DialogueSessionState.EXPIRED
                && state != DialogueSessionState.REJECTED;
    }

    private DialogueSession update(String sessionId, java.util.function.Function<DialogueSession, DialogueSession> updater) {
        String normalized = sanitize(sessionId);
        DialogueSession existing = sessions.get(normalized);
        if (existing == null) {
            throw new IllegalArgumentException("session not found: " + normalized);
        }
        DialogueSession updated = updater.apply(existing);
        sessions.put(normalized, updated);
        return updated;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
