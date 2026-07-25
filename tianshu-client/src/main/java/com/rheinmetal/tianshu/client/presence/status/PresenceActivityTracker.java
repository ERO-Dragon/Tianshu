package com.rheinmetal.tianshu.client.presence.status;

import com.rheinmetal.tianshu.client.presence.model.PresenceActivitySnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresencePrimaryState;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityAction;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityType;
import com.rheinmetal.tianshu.protocol.ProtocolSourceIds;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class PresenceActivityTracker {
    private final LongSupplier clock;
    private final Map<ActivityKey, ActiveActivity> activities = new LinkedHashMap<>();
    private boolean worldSessionActive;
    private long worldSessionStartedAtMillis;

    public PresenceActivityTracker() {
        this(System::currentTimeMillis);
    }

    public PresenceActivityTracker(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void startWorldSession() {
        activities.clear();
        worldSessionStartedAtMillis = clock.getAsLong();
        worldSessionActive = true;
    }

    public synchronized void stopWorldSession() {
        worldSessionActive = false;
        worldSessionStartedAtMillis = 0L;
        activities.clear();
    }

    public synchronized void accept(String sourceId, PresenceActivityPayload payload) {
        String source = normalize(sourceId);
        if (!worldSessionActive || source.isEmpty() || payload == null || !sourceAllowed(source, payload.activityType())) {
            return;
        }
        if (payload.occurredAtMillis() < worldSessionStartedAtMillis) {
            return;
        }
        ActivityKey key = new ActivityKey(source, payload.activityId(), payload.activityType());
        if (payload.action() == PresenceActivityAction.ENDED) {
            activities.remove(key);
            return;
        }
        activities.put(key, new ActiveActivity(
                key,
                payload.occurredAtMillis(),
                payload.occurredAtMillis() + payload.ttlMillis()
        ));
    }

    public synchronized PresenceActivitySnapshot snapshot() {
        long now = clock.getAsLong();
        if (!worldSessionActive) {
            return PresenceActivitySnapshot.idle(now);
        }
        activities.values().removeIf(activity -> activity.expired(now));
        boolean listening = activities.values().stream()
                .anyMatch(activity -> activity.key().activityType() == PresenceActivityType.LISTENING);
        ActiveActivity primary = null;
        for (ActiveActivity activity : activities.values()) {
            if (activity.key().activityType() == PresenceActivityType.LISTENING) {
                continue;
            }
            if (primary == null || higherPriority(activity, primary)) {
                primary = activity;
            }
        }
        if (primary == null) {
            return new PresenceActivitySnapshot(PresencePrimaryState.IDLE, listening, "", now);
        }
        return new PresenceActivitySnapshot(
                primaryState(primary.key().activityType()),
                listening,
                primary.key().sourceId(),
                Math.max(now, primary.occurredAtMillis())
        );
    }

    private boolean sourceAllowed(String sourceId, PresenceActivityType type) {
        return switch (type) {
            case THINKING, RESPONDING -> ProtocolSourceIds.AX.equals(sourceId);
            case LISTENING -> ProtocolSourceIds.ASR.equals(sourceId);
            case LOADING, PROCESSING_TASK -> true;
        };
    }

    private boolean higherPriority(ActiveActivity candidate, ActiveActivity current) {
        int candidatePriority = priority(candidate.key().activityType());
        int currentPriority = priority(current.key().activityType());
        if (candidatePriority != currentPriority) {
            return candidatePriority > currentPriority;
        }
        return candidate.occurredAtMillis() >= current.occurredAtMillis();
    }

    private int priority(PresenceActivityType type) {
        return switch (type) {
            case RESPONDING -> 50;
            case THINKING -> 40;
            case PROCESSING_TASK -> 30;
            case LOADING -> 20;
            case LISTENING -> 0;
        };
    }

    private PresencePrimaryState primaryState(PresenceActivityType type) {
        return switch (type) {
            case LOADING -> PresencePrimaryState.LOADING;
            case PROCESSING_TASK -> PresencePrimaryState.PROCESSING_TASK;
            case THINKING -> PresencePrimaryState.THINKING;
            case RESPONDING -> PresencePrimaryState.RESPONDING;
            case LISTENING -> PresencePrimaryState.IDLE;
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record ActivityKey(String sourceId, String activityId, PresenceActivityType activityType) {
    }

    private record ActiveActivity(ActivityKey key, long occurredAtMillis, long expiresAtMillis) {
        private boolean expired(long nowMillis) {
            return expiresAtMillis > 0L && nowMillis >= expiresAtMillis;
        }
    }
}
