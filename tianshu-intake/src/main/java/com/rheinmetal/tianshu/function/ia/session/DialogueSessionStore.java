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
import java.util.function.UnaryOperator;

/**
 * The sessions the arbiter has created in this runtime, and the one active session per player.
 *
 * <p>Every turn creates a session and most of them are terminal within seconds, so the store keeps a bounded
 * history instead of the whole session lifetime: diagnostics need recent turns, not every turn ever played.
 * The maps are insertion ordered so eviction removes the oldest records first, and the whole store is guarded
 * by one monitor — a turn is a low-frequency event, and one lock is what keeps "session map + player index +
 * eviction" a single consistent step.</p>
 *
 * <p><b>State transitions are checked here.</b> A session that already reached a terminal state can never be
 * activated again, and a control that arrives after the session was released answers "already gone" instead
 * of rewriting history: the controller may still process a release while the arbiter is between claiming and
 * activating its own turn.</p>
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

    /** Marks a claimed session active; a session that is already terminal or not yet claimed is left alone. */
    public synchronized Optional<DialogueSession> activate(String sessionId, long nowMillis) {
        return updateActive(sessionId, session -> session.activate(nowMillis),
                session -> session.state() == DialogueSessionState.CLAIMED);
    }

    public synchronized Optional<DialogueSession> extendProcessing(String sessionId, long nowMillis, long processingDeadlineMillis) {
        return updateActive(sessionId, session -> session.extendProcessing(nowMillis, processingDeadlineMillis),
                session -> activeState(session.state()));
    }

    public synchronized Optional<DialogueSession> interrupting(String sessionId, long nowMillis) {
        return updateActive(sessionId, session -> session.interrupting(nowMillis),
                session -> activeState(session.state()));
    }

    public synchronized Optional<DialogueSession> release(String sessionId, DialogueReleaseReason reason, long nowMillis) {
        Optional<DialogueSession> released = updateActive(sessionId,
                session -> session.terminal(DialogueSessionState.RELEASED, reason, nowMillis),
                session -> !isTerminal(session.state()));
        released.ifPresent(session -> {
            activeSessionByPlayer.remove(session.playerId(), session.sessionId());
            pruneTerminalSessions();
        });
        return released;
    }

    public synchronized Optional<DialogueSession> releaseIfPresent(String sessionId, DialogueReleaseReason reason, long nowMillis) {
        return release(sessionId, reason, nowMillis);
    }

    public synchronized Optional<DialogueSession> expire(String sessionId, long nowMillis) {
        Optional<DialogueSession> expired = updateActive(sessionId,
                session -> session.terminal(DialogueSessionState.EXPIRED, DialogueReleaseReason.EXPIRED, nowMillis),
                session -> activeState(session.state()));
        expired.ifPresent(session -> {
            activeSessionByPlayer.remove(session.playerId(), session.sessionId());
            pruneTerminalSessions();
        });
        return expired;
    }

    public synchronized Optional<DialogueSession> expireIfPresent(String sessionId, long nowMillis) {
        return expire(sessionId, nowMillis);
    }

    public synchronized List<DialogueSession> releaseByOwnerModule(String moduleId, DialogueReleaseReason reason, long nowMillis) {
        String normalized = sanitize(moduleId);
        if (normalized.isBlank()) {
            return List.of();
        }
        // Collect first, then transition: releasing can evict terminal records, which would mutate the map
        // the collection was iterating.
        List<String> targets = sessions.values().stream()
                .filter(session -> session.ownerModuleId().equals(normalized) && activeState(session.state()))
                .map(DialogueSession::sessionId)
                .toList();
        List<DialogueSession> released = new java.util.ArrayList<>(targets.size());
        for (String sessionId : targets) {
            release(sessionId, reason, nowMillis).ifPresent(released::add);
        }
        return List.copyOf(released);
    }

    public synchronized List<DialogueSession> releaseByOwnerParticipant(String moduleId, String participantId, DialogueReleaseReason reason, long nowMillis) {
        String normalizedModule = sanitize(moduleId);
        String normalizedParticipant = sanitize(participantId);
        if (normalizedModule.isBlank() || normalizedParticipant.isBlank()) {
            return List.of();
        }
        List<String> targets = sessions.values().stream()
                .filter(session -> session.ownedBy(normalizedModule, normalizedParticipant) && activeState(session.state()))
                .map(DialogueSession::sessionId)
                .toList();
        List<DialogueSession> released = new java.util.ArrayList<>(targets.size());
        for (String sessionId : targets) {
            release(sessionId, reason, nowMillis).ifPresent(released::add);
        }
        return List.copyOf(released);
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

    /**
     * Makes an already active session the player's active one again.
     *
     * <p>Used when a turn claims a new session and then fails before it could preempt the previous one:
     * claiming moved the player index, releasing the failed session removed it, and the running turn has to
     * get its index entry back or it becomes unreachable until it expires.</p>
     */
    public synchronized void restoreActiveSession(DialogueSession session) {
        if (session == null) {
            return;
        }
        DialogueSession stored = sessions.get(sanitize(session.sessionId()));
        if (stored != null && activeState(stored.state())) {
            activeSessionByPlayer.put(stored.playerId(), stored.sessionId());
        }
    }

    public synchronized List<DialogueSession> expireOverdue(long nowMillis) {
        List<String> targets = sessions.values().stream()
                .filter(session -> activeState(session.state()) && session.processingDeadlineMillis() <= nowMillis)
                .map(DialogueSession::sessionId)
                .toList();
        List<DialogueSession> expired = new java.util.ArrayList<>(targets.size());
        for (String sessionId : targets) {
            expire(sessionId, nowMillis).ifPresent(expired::add);
        }
        return List.copyOf(expired);
    }

    public synchronized void clear() {
        sessions.clear();
        activeSessionByPlayer.clear();
    }

    private Optional<DialogueSession> updateActive(
            String sessionId,
            UnaryOperator<DialogueSession> updater,
            java.util.function.Predicate<DialogueSession> allowed
    ) {
        DialogueSession existing = sessions.get(sanitize(sessionId));
        if (existing == null || !allowed.test(existing)) {
            return Optional.empty();
        }
        DialogueSession updated = updater.apply(existing);
        sessions.put(updated.sessionId(), updated);
        return Optional.of(updated);
    }

    static boolean activeState(DialogueSessionState state) {
        return state != null
                && state != DialogueSessionState.RELEASED
                && state != DialogueSessionState.EXPIRED
                && state != DialogueSessionState.REJECTED;
    }

    private static boolean isTerminal(DialogueSessionState state) {
        return state == DialogueSessionState.RELEASED
                || state == DialogueSessionState.EXPIRED
                || state == DialogueSessionState.REJECTED;
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

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}