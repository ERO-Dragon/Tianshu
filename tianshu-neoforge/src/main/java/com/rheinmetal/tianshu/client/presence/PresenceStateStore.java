package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceStatusSnapshot;
import com.rheinmetal.tianshu.client.presence.status.PresenceStatusPriority;

public final class PresenceStateStore {
    private final Object lock = new Object();
    private PresenceContextSnapshot contextSnapshot = PresenceContextSnapshot.empty();
    private PresenceContextSnapshot detailedContextSnapshot = PresenceContextSnapshot.empty();
    private PresenceStatusSnapshot statusSnapshot = PresenceStatusSnapshot.idle();

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

}
