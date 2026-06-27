package com.rheinmetal.tianshu.client.presence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class PresenceStateStore {
    private static final int MAX_RECENT_EVENTS = 32;

    private final Object lock = new Object();
    private PresenceContextSnapshot contextSnapshot = PresenceContextSnapshot.empty();
    private PresenceContextSnapshot detailedContextSnapshot = PresenceContextSnapshot.empty();
    private PresenceStatusSnapshot statusSnapshot = PresenceStatusSnapshot.idle();
    private final ArrayDeque<PresenceInteractionEvent> recentEvents = new ArrayDeque<>();

    public void updateContext(PresenceContextSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        synchronized (lock) {
            contextSnapshot = snapshot;
        }
    }

    public PresenceContextSnapshot contextSnapshot() {
        synchronized (lock) {
            return contextSnapshot;
        }
    }

    public void updateDetailedContext(PresenceContextSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        synchronized (lock) {
            detailedContextSnapshot = snapshot;
            contextSnapshot = snapshot;
        }
    }

    public PresenceContextSnapshot freshestDetailedContextSnapshot(long maxAgeMillis) {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (maxAgeMillis <= 0L || now - detailedContextSnapshot.capturedAtMillis() <= maxAgeMillis) {
                return detailedContextSnapshot.withRealtimeFieldsFrom(contextSnapshot);
            }
            return contextSnapshot;
        }
    }

    public void updateStatus(PresenceStatusSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        synchronized (lock) {
            if (PresenceStatusPriority.shouldReplace(statusSnapshot, snapshot, System.currentTimeMillis())) {
                statusSnapshot = snapshot;
            }
        }
    }

    public PresenceStatusSnapshot statusSnapshot() {
        synchronized (lock) {
            PresenceStatusSnapshot current = statusSnapshot;
            if (current.expired(System.currentTimeMillis())) {
                statusSnapshot = PresenceStatusSnapshot.idle();
            }
            return statusSnapshot;
        }
    }

    public void recordEvent(PresenceInteractionEvent event) {
        if (event == null) {
            return;
        }
        synchronized (lock) {
            recentEvents.addLast(event);
            while (recentEvents.size() > MAX_RECENT_EVENTS) {
                recentEvents.removeFirst();
            }
        }
    }

    public List<PresenceInteractionEvent> recentEvents(int limit) {
        synchronized (lock) {
            int max = limit <= 0 ? MAX_RECENT_EVENTS : Math.min(limit, MAX_RECENT_EVENTS);
            List<PresenceInteractionEvent> all = new ArrayList<>(recentEvents);
            int fromIndex = Math.max(0, all.size() - max);
            return List.copyOf(all.subList(fromIndex, all.size()));
        }
    }
}
