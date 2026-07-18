package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackPlacement;
import com.rheinmetal.tianshu.protocol.Priority;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Comparator;

public final class TtsSpeechSessionCoordinator {
    private static final int MAX_PENDING_SESSIONS = 8;
    private final Map<TtsSpeechSessionKey, TtsSpeechSession> sessions = new HashMap<>();
    private final ArrayDeque<TtsSpeechSession> suspended = new ArrayDeque<>();
    private final PriorityQueue<TtsSpeechSession> pending = new PriorityQueue<>(
            Comparator.<TtsSpeechSession>comparingInt(session -> session.priority.weight())
                    .reversed()
                    .thenComparingLong(session -> session.admissionSequence)
    );
    private final ArrayDeque<Termination> terminations = new ArrayDeque<>();
    private TtsSpeechSession active;
    private SentenceWork currentWork;
    private long workSequence;
    private long admissionSequence;

    public synchronized Admission admit(TtsSpeechSessionKey key, TtsPlaybackPlacement placement) {
        return admit(key, placement, Priority.NORMAL);
    }

    public synchronized Admission admit(TtsSpeechSessionKey key, TtsPlaybackPlacement placement, Priority priority) {
        if (key == null) {
            throw new IllegalArgumentException("TTS speech session key is required");
        }
        TtsSpeechSession existing = sessions.get(key);
        if (existing != null) {
            return new Admission(AdmissionState.EXISTING, null);
        }

        TtsPlaybackPlacement effectivePlacement = placement == null
                ? TtsPlaybackPlacement.QUEUE_AFTER_SESSION
                : placement;
        if (effectivePlacement == TtsPlaybackPlacement.DROP_IF_BUSY && isBusy()) {
            return new Admission(AdmissionState.DROPPED, null);
        }
        if (pendingSessionCount() >= MAX_PENDING_SESSIONS) {
            return new Admission(AdmissionState.REJECTED, null);
        }
        TtsSpeechSession incoming = new TtsSpeechSession(key, effectivePlacement, priority, ++admissionSequence);
        sessions.put(key, incoming);
        if (active == null) {
            active = incoming;
            return new Admission(AdmissionState.ACCEPTED, null);
        }

        return switch (effectivePlacement) {
            case DROP_IF_BUSY, QUEUE_AFTER_SESSION -> {
                pending.add(incoming);
                yield new Admission(AdmissionState.ACCEPTED, null);
            }
            case INSERT_AFTER_SESSION -> {
                active.afterSession.addLast(incoming);
                yield new Admission(AdmissionState.ACCEPTED, null);
            }
            case INSERT_AFTER_SENTENCE -> {
                if (currentWork == null) {
                    suspendActiveAndActivate(incoming);
                } else {
                    active.afterSentence.addLast(incoming);
                }
                yield new Admission(AdmissionState.ACCEPTED, null);
            }
            case CANCEL_SENTENCE_AND_PLAY -> {
                SentenceWork cancelled = currentWork;
                currentWork = null;
                suspendActiveAndActivate(incoming);
                yield new Admission(AdmissionState.ACCEPTED, cancelled);
            }
            case CANCEL_SESSION_AND_PLAY -> {
                SentenceWork cancelled = currentWork;
                currentWork = null;
                cancelActiveSession();
                active = incoming;
                yield new Admission(AdmissionState.ACCEPTED, cancelled);
            }
        };
    }

    public synchronized void appendSentence(TtsSpeechSessionKey key, String sentence) {
        TtsSpeechSession session = sessions.get(key);
        if (session == null || session.cancelled || sentence == null || sentence.isBlank()) {
            return;
        }
        session.sentences.addLast(sentence.trim());
    }

    public synchronized void end(TtsSpeechSessionKey key) {
        TtsSpeechSession session = sessions.get(key);
        if (session == null) {
            return;
        }
        session.ended = true;
        if (session == active && currentWork == null && session.sentences.isEmpty()) {
            finishActive();
        }
    }

    public synchronized Optional<SentenceWork> poll() {
        if (currentWork != null) {
            return Optional.empty();
        }
        while (active != null) {
            if (activateBoundaryInsertion()) {
                continue;
            }
            if (!active.sentences.isEmpty()) {
                currentWork = new SentenceWork(active.key, active.sentences.removeFirst(), ++workSequence);
                return Optional.of(currentWork);
            }
            if (active.ended || active.cancelled) {
                finishActive();
                continue;
            }
            return Optional.empty();
        }
        activatePending();
        return active == null ? Optional.empty() : poll();
    }

    public synchronized void complete(SentenceWork work) {
        if (work == null || !work.equals(currentWork)) {
            return;
        }
        currentWork = null;
        if (activateBoundaryInsertion()) {
            return;
        }
        if (active != null && active.ended && active.sentences.isEmpty()) {
            finishActive();
        }
    }

    public synchronized void clear() {
        sessions.values().forEach(session ->
                terminations.addLast(new Termination(session.key, TerminationReason.CANCELLED)));
        sessions.clear();
        suspended.clear();
        pending.clear();
        active = null;
        currentWork = null;
    }

    public synchronized Optional<TtsSpeechSessionKey> activeKey() {
        return Optional.ofNullable(active == null ? null : active.key);
    }

    public synchronized boolean contains(TtsSpeechSessionKey key) {
        return key != null && sessions.containsKey(key);
    }

    public synchronized boolean cancel(TtsSpeechSessionKey key) {
        TtsSpeechSession session = sessions.get(key);
        if (session == null) {
            return false;
        }
        session.cancelled = true;
        session.sentences.clear();
        detachFromParents(session);
        pending.remove(session);
        suspended.remove(session);
        if (active == session) {
            currentWork = null;
            cancelActiveSession();
            finishActive();
        } else {
            promoteChildren(session);
            sessions.remove(key, session);
            terminations.addLast(new Termination(key, TerminationReason.CANCELLED));
        }
        return true;
    }

    public synchronized java.util.List<Termination> drainTerminations() {
        java.util.List<Termination> drained = java.util.List.copyOf(terminations);
        terminations.clear();
        return drained;
    }

    private boolean isBusy() {
        return active != null || currentWork != null || !pending.isEmpty() || !suspended.isEmpty();
    }

    private void suspendActiveAndActivate(TtsSpeechSession incoming) {
        TtsSpeechSession previous = active;
        if (previous != null && previous != incoming && previous.resumable()) {
            suspended.push(previous);
        } else if (previous != null && previous != incoming) {
            sessions.remove(previous.key, previous);
        }
        active = incoming;
    }

    private void cancelActiveSession() {
        TtsSpeechSession cancelled = active;
        if (cancelled == null) {
            return;
        }
        cancelled.cancelled = true;
        promoteChildren(cancelled);
        sessions.remove(cancelled.key, cancelled);
        terminations.addLast(new Termination(cancelled.key, TerminationReason.CANCELLED));
        active = null;
    }

    private void finishActive() {
        TtsSpeechSession finished = active;
        active = null;
        if (finished != null) {
            sessions.remove(finished.key, finished);
            terminations.addLast(new Termination(
                    finished.key,
                    finished.cancelled ? TerminationReason.CANCELLED : TerminationReason.COMPLETED
            ));
            if (!finished.afterSession.isEmpty()) {
                TtsSpeechSession next = finished.afterSession.removeFirst();
                while (!finished.afterSession.isEmpty()) {
                    next.afterSession.addLast(finished.afterSession.removeFirst());
                }
                active = next;
                return;
            }
        }
        while (!suspended.isEmpty()) {
            TtsSpeechSession resumed = suspended.pop();
            if (resumed.resumable()) {
                active = resumed;
                return;
            }
            sessions.remove(resumed.key, resumed);
        }
        activatePending();
    }

    private void activatePending() {
        while (active == null && !pending.isEmpty()) {
            TtsSpeechSession next = pending.remove();
            if (!next.cancelled) {
                active = next;
            }
        }
    }

    private boolean activateBoundaryInsertion() {
        if (active == null || active.afterSentence.isEmpty()) {
            return false;
        }
        TtsSpeechSession incoming = active.afterSentence.removeFirst();
        while (!active.afterSentence.isEmpty()) {
            incoming.afterSession.addLast(active.afterSentence.removeFirst());
        }
        suspendActiveAndActivate(incoming);
        return true;
    }

    private void promoteChildren(TtsSpeechSession session) {
        while (!session.afterSentence.isEmpty()) {
            pending.add(session.afterSentence.removeFirst());
        }
        while (!session.afterSession.isEmpty()) {
            pending.add(session.afterSession.removeFirst());
        }
    }

    private void detachFromParents(TtsSpeechSession session) {
        for (TtsSpeechSession candidate : sessions.values()) {
            if (candidate != session) {
                candidate.afterSentence.remove(session);
                candidate.afterSession.remove(session);
            }
        }
    }

    private int pendingSessionCount() {
        return Math.max(0, sessions.size() - (active == null ? 0 : 1));
    }

    public enum AdmissionState {
        ACCEPTED,
        EXISTING,
        DROPPED,
        REJECTED
    }

    public record Admission(AdmissionState state, SentenceWork cancelledWork) {
        public boolean cancelledSentence() {
            return cancelledWork != null;
        }
    }

    public record SentenceWork(TtsSpeechSessionKey sessionKey, String text, long sequence) {
    }

    public enum TerminationReason {
        COMPLETED,
        CANCELLED
    }

    public record Termination(TtsSpeechSessionKey sessionKey, TerminationReason reason) {
    }

}
