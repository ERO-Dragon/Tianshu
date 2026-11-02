package com.rheinmetal.tianshu.function.echo.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The live sessions of this runtime.
 *
 * <p>It holds a session only while that session can still change. Keeping finished ones was the one unbounded
 * structure in the module: a session is created per sentence and its request carries the whole text, so a long
 * play session accumulated every line ever spoken. A terminal session has nothing left to stop, so nothing
 * that reads this map needs it to stay.</p>
 */
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

    public synchronized Optional<TtsSession> cancel(String requestId, String reason) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        TtsSession session = sessions.get(requestId.trim());
        if (session == null || session.isTerminal()) {
            return Optional.empty();
        }
        session.cancel(reason);
        sessions.remove(session.request().requestId(), session);
        if (active == session) {
            active = null;
        }
        return Optional.of(session);
    }

    public synchronized List<TtsSession> cancelAll(String reason) {
        List<TtsSession> cancelled = sessions.values().stream()
                .filter(session -> !session.isTerminal())
                .toList();
        for (TtsSession session : cancelled) {
            session.cancel(reason);
        }
        sessions.values().removeIf(TtsSession::isTerminal);
        if (active != null && active.isTerminal()) {
            active = null;
        }
        return cancelled;
    }

    public synchronized TtsSession complete(TtsSession session) {
        if (session != null) {
            session.complete();
            sessions.remove(session.request().requestId(), session);
        }
        if (active == session) {
            active = null;
        }
        return session;
    }

    public synchronized void clear() {
        sessions.clear();
        active = null;
    }

    /**
     * How many sessions are still live. Package-visible so a test can assert the invariant this class exists
     * to keep — that a terminal session leaves the map — instead of trusting a comment.
     */
    synchronized int liveCount() {
        return sessions.size();
    }
}
