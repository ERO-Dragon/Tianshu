package com.rheinmetal.tianshu.client.presence;

public final class PresenceStatusPriority {
    private PresenceStatusPriority() {
    }

    public static boolean shouldReplace(PresenceStatusSnapshot current, PresenceStatusSnapshot candidate, long nowMillis) {
        if (candidate == null) {
            return false;
        }
        if (current == null || current.expired(nowMillis)) {
            return true;
        }
        int candidatePriority = priority(candidate);
        int currentPriority = priority(current);
        if (candidatePriority != currentPriority) {
            return candidatePriority > currentPriority;
        }
        return candidate.updatedAtMillis() >= current.updatedAtMillis();
    }

    private static int priority(PresenceStatusSnapshot snapshot) {
        if (snapshot.severity() == PresenceSeverity.ERROR) {
            return 100;
        }
        return switch (snapshot.statusType()) {
            case ERROR -> 100;
            case LISTENING -> 90;
            case TRANSCRIBING -> 80;
            case THINKING -> 70;
            case SPEAKING -> 60;
            case COMPRESSING -> 50;
            case IDLE -> 10;
        };
    }
}
