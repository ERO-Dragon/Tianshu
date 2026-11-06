package com.rheinmetal.tianshu.function.ia.session;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSession;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSessionState;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The sessions the arbiter has created in this runtime, and the one active session per player.
 *
 * <p>Every turn creates a session and most of them are terminal within seconds, so the store keeps a bounded
 * history instead of the whole session lifetime: diagnostics need recent turns, not every turn ever played.
 * The maps are insertion ordered so eviction removes the oldest records first, and the whole store is guarded
 * by one monitor — a turn is a low-frequency event, and one lock is what keeps "session map + player index +
 * eviction" a single consistent step.</p>
 */
public final class DialogueSessionStore {
    /** How many terminal sessions are kept for diagnostics; active sessions are never evicted. */
    private static final int MAX_TERMINAL_SESSIONS = 256;

    private final Map<String, DialogueSession> sessions = new LinkedHashMap<>();
    private final Map<String, String> activeSessionByPlayer = new HashMap<>();

    public synchronized DialogueSession createClaimed(String playerId, String turnId, DialogueParticipantDescriptor owner, long nowMillis) {
        String sessionId = UUID.randomUUID().toString();
        DialogueSession pending = new DialogueSession(sessionId, playerId, "", "", DialogueSessionState.PENDING, turnId, nowMillis, nowMillis, nowMillis, null);
        DialogueSession claimed = pending.claim(owner, turnId, nowMillis);
        sessions.put(sessionId, claimed);
        activeSessionByPlayer.put(playerId, sessionId);
        pruneTerminalSessions();
        return claimed;
    }

    public synchronized Optional<DialogueSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sanitize(sessionId)));
    }

    public synchronized Optional<DialogueSession> activeForPlayer(String playerId, long nowMillis) {
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

    public synchronized DialogueSession activate(String sessionId, long nowMillis) {
        return update(sessionId, session -> session.activate(nowMillis));
    }

    public synchronized DialogueSession extendProcessing(String sessionId, long nowMillis, long processingDeadlineMillis) {
        return update(sessionId, session -> session.extendProcessing(nowMillis, processingDeadlineMillis));
    }

    public synchronized DialogueSession interrupting(String sessionId, long nowMillis) {
        return update(sessionId, session -> session.interrupting(nowMillis));
    }

    public synchronized DialogueSession release(String sessionId, DialogueReleaseReason reason, long nowMillis) {
        DialogueSession released = update(sessionId, session -> session.terminal(DialogueSessionState.RELEASED, reason, nowMillis));
        activeSessionByPlayer.remove(released.playerId(), released.sessionId());
        pruneTerminalSessions();
        return released;
    }

    public synchronized Optional<DialogueSession> releaseIfPresent(String sessionId, DialogueReleaseReason reason, long nowMillis) {
        return updateIfPresent(sessionId, session -> session.terminal(DialogueSessionState.RELEASED, reason, nowMillis))
                .map(released -> {
                    activeSessionByPlayer.remove(released.playerId(), released.sessionId());
                    return released;
                });
    }

    public synchronized DialogueSession expire(String sessionId, long nowMillis) {
        DialogueSession expired = update(sessionId, session -> session.terminal(DialogueSessionState.EXPIRED, DialogueReleaseReason.EXPIRED, nowMillis));
        activeSessionByPlayer.remove(expired.playerId(), expired.sessionId());
        pruneTerminalSessions();
        return expired;
    }

    public synchronized Optional<DialogueSession> expireIfPresent(String sessionId, long nowMillis) {
        return updateIfPresent(sessionId, session -> session.terminal(DialogueSessionState.EXPIRED, DialogueReleaseReason.EXPIRED, nowMillis))
                .map(expired -> {
                    activeSessionByPlayer.remove(expired.playerId(), expired.sessionId());
                    return expired;
                });
    }

    public synchronized List<DialogueSession> releaseByOwnerModule(String moduleId, DialogueReleaseReason reason, long nowMillis) {
        String normalized = sanitize(moduleId);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<DialogueSession> released = sessions.values().stream()
                .filter(session -> session.ownerModuleId().equals(normalized) && activeState(session.state()))
                .map(session -> releaseIfPresent(session.sessionId(), reason, nowMillis))
                .flatMap(Optional::stream)
                .toList();
        pruneTerminalSessions();
        return released;
    }

    public synchronized List<DialogueSession> releaseByOwnerParticipant(String moduleId, String participantId, DialogueReleaseReason reason, long nowMillis) {
        String normalizedModule = sanitize(moduleId);
        String normalizedParticipant = sanitize(participantId);
        if (normalizedModule.isBlank() || normalizedParticipant.isBlank()) {
            return List.of();
        }
        List<DialogueSession> released = sessions.values().stream()
                .filter(session -> session.ownedBy(normalizedModule, normalizedParticipant) && activeState(session.state()))
                .map(session -> releaseIfPresent(session.sessionId(), reason, nowMillis))
                .flatMap(Optional::stream)
                .toList();
        pruneTerminalSessions();
        return released;
    }

    public synchronized DialogueSession reject(String playerId, String turnId, long nowMillis) {
        String sessionId = UUID.randomUUID().toString();
        DialogueSession rejected = new DialogueSession(sessionId, playerId, "", "", DialogueSessionState.REJECTED, turnId, nowMillis, nowMillis, nowMillis, DialogueReleaseReason.REJECTED);
        sessions.put(sessionId, rejected);
        pruneTerminalSessions();
        return rejected;
    }

    /** Recent sessions, oldest first; the store is already bounded, so this is what diagnostics may show. */
    public synchronized List<DialogueSession> snapshot() {
        return sessions.values().stream()
                .sorted(Comparator.comparingLong(DialogueSession::createdAtMillis)
                        .thenComparing(DialogueSession::sessionId))
                .toList();
    }

    public synchronized List<DialogueSession> expireOverdue(long nowMillis) {
        List<DialogueSession> expired = sessions.values().stream()
                .filter(session -> activeState(session.state()) && session.processingDeadlineMillis() <= nowMillis)
                .map(session -> expireIfPresent(session.sessionId(), nowMillis))
                .flatMap(Optional::stream)
                .toList();
        pruneTerminalSessions();
        return expired;
    }

    public synchronized void clear() {
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

    /**
     * Drops the oldest terminal sessions once the history exceeds its cap.
     *
     * <p>Active sessions are skipped rather than evicted: a turn that is still in flight is the one record a
     * running turn needs, and there can only be one per player.</p>
     */
    private void pruneTerminalSessions() {
        int terminal = 0;
        for (DialogueSession session : sessions.values()) {
            if (!activeState(session.state())) {
                terminal++;
            }
        }
        if (terminal <= MAX_TERMINAL_SESSIONS) {
            return;
        }
        Iterator<Map.Entry<String, DialogueSession>> iterator = sessions.entrySet().iterator();
        while (terminal > MAX_TERMINAL_SESSIONS && iterator.hasNext()) {
            Map.Entry<String, DialogueSession> entry = iterator.next();
            if (activeState(entry.getValue().state())) {
                continue;
            }
            iterator.remove();
            terminal--;
        }
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
