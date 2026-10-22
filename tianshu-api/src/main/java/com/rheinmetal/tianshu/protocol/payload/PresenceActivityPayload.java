package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.Objects;

public record PresenceActivityPayload(
        String activityId,
        PresenceActivityType activityType,
        PresenceActivityAction action,
        long occurredAtMillis,
        long ttlMillis
) implements ITianshuPayload {
    public PresenceActivityPayload {
        if (activityId == null || activityId.isBlank()) {
            throw new IllegalArgumentException("activityId cannot be blank");
        }
        activityId = activityId.trim();
        activityType = Objects.requireNonNull(activityType, "activityType");
        action = Objects.requireNonNull(action, "action");
        occurredAtMillis = occurredAtMillis > 0L ? occurredAtMillis : System.currentTimeMillis();
        ttlMillis = Math.max(0L, ttlMillis);
        if (action == PresenceActivityAction.STARTED && ttlMillis == 0L) {
            throw new IllegalArgumentException("started activity requires a positive ttlMillis");
        }
    }

    public static PresenceActivityPayload started(String activityId, PresenceActivityType type, long ttlMillis) {
        return new PresenceActivityPayload(
                activityId,
                type,
                PresenceActivityAction.STARTED,
                System.currentTimeMillis(),
                ttlMillis
        );
    }

    public static PresenceActivityPayload ended(String activityId, PresenceActivityType type) {
        return new PresenceActivityPayload(
                activityId,
                type,
                PresenceActivityAction.ENDED,
                System.currentTimeMillis(),
                0L
        );
    }
}
