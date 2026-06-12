package com.rheinmetal.tianshu.function.tts.runtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class TtsSessionManager {
    private final Map<String, TtsSession> sessions = new LinkedHashMap<>();
    private TtsSession active;

    public synchronized TtsSession create(TtsRequest request) {
        TtsSession session = new TtsSession(request);
        sessions.put(request.requestId(), session);
        return session;
    }

    public synchronized void activate(TtsSession session) {
        if (session != null && !session.isTerminal()) {
            active = session;
        }
    }

    public synchronized Optional<TtsSession> active() {
        return Optional.ofNullable(active);
    }

    public synchronized Optional<TtsSession> find(String requestId) {
        return Optional.ofNullable(sessions.get(requestId));
    }

    public synchronized Optional<TtsSession> cancel(String requestId, String reason) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        TtsSession session = sessions.get(requestId.trim());
        if (session == null || session.isTerminal()) {
            return Optional.empty();
        }
        session.cancel(reason);
        if (active == session) {
            active = null;
        }
        return Optional.of(session);
    }

    public synchronized List<TtsSession> cancelRequestGroup(String requestIdOrGroup, String reason) {
        if (requestIdOrGroup == null || requestIdOrGroup.isBlank()) {
            return List.of();
        }
        String normalized = requestIdOrGroup.trim();
        String groupPrefix = normalized.endsWith(":") ? normalized : normalized + ":";
        List<TtsSession> cancelled = sessions.values().stream()
                .filter(session -> !session.isTerminal())
                .filter(session -> session.request().requestId().equals(normalized)
                        || session.request().requestId().startsWith(groupPrefix)
                        || session.request().groupId().equals(normalized)
                        || session.request().groupId().startsWith(groupPrefix))
                .toList();
        for (TtsSession session : cancelled) {
            session.cancel(reason);
        }
        if (active != null && active.isTerminal()) {
            active = null;
        }
        return cancelled;
    }

    public synchronized List<TtsSession> cancelGroup(String groupId, String reason) {
        if (groupId == null || groupId.isBlank()) {
            return List.of();
        }
        String normalized = groupId.trim();
        List<TtsSession> cancelled = sessions.values().stream()
                .filter(session -> !session.isTerminal())
                .filter(session -> session.request().groupId().equals(normalized))
                .toList();
        for (TtsSession session : cancelled) {
            session.cancel(reason);
        }
        if (active != null && active.isTerminal()) {
            active = null;
        }
        return cancelled;
    }

    public synchronized List<TtsSession> cancelAll(String reason) {
        List<TtsSession> cancelled = sessions.values().stream()
                .filter(session -> !session.isTerminal())
                .toList();
        for (TtsSession session : cancelled) {
            session.cancel(reason);
        }
        if (active != null && active.isTerminal()) {
            active = null;
        }
        return cancelled;
    }

    public synchronized Collection<TtsSession> snapshot() {
        return List.copyOf(sessions.values());
    }

    public synchronized TtsSession complete(TtsSession session) {
        if (session != null) {
            session.complete();
        }
        if (active == session) {
            active = null;
        }
        return session;
    }

    public synchronized TtsSession cancelActive(String reason) {
        if (active != null && !active.isTerminal()) {
            TtsSession cancelled = active;
            cancelled.cancel(reason);
            active = null;
            return cancelled;
        }
        active = null;
        return null;
    }

    public synchronized void clear() {
        sessions.clear();
        active = null;
    }
}
