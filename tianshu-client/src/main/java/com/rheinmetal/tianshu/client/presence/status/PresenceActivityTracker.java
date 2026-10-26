package com.rheinmetal.tianshu.client.presence.status;

import com.rheinmetal.tianshu.client.presence.model.PresenceActivitySnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresencePrimaryState;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityAction;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class PresenceActivityTracker {
    private final LongSupplier clock;
    private final Map<ActivityKey, ActiveActivity> activities = new LinkedHashMap<>();
    private boolean worldSessionActive;
    private long worldSessionStartedAtMillis;
    private PresenceActivitySnapshot cachedSnapshot = PresenceActivitySnapshot.idle(0L);
    private long cachedSnapshotExpiryAtMillis;
    private boolean snapshotDirty = true;

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
        cachedSnapshot = PresenceActivitySnapshot.idle(worldSessionStartedAtMillis);
        cachedSnapshotExpiryAtMillis = 0L;
        snapshotDirty = true;
    }

    public synchronized void stopWorldSession() {
        worldSessionActive = false;
        worldSessionStartedAtMillis = 0L;
        activities.clear();
        cachedSnapshot = PresenceActivitySnapshot.idle(clock.getAsLong());
        cachedSnapshotExpiryAtMillis = 0L;
        snapshotDirty = true;
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
            snapshotDirty = true;
            return;
        }
        activities.put(key, new ActiveActivity(
                key,
                payload.occurredAtMillis(),
                payload.occurredAtMillis() + payload.ttlMillis()
        ));
        snapshotDirty = true;
    }

    public synchronized PresenceActivitySnapshot snapshot() {
        long now = clock.getAsLong();
        if (!worldSessionActive) {
            if (snapshotDirty) {
                cachedSnapshot = PresenceActivitySnapshot.idle(now);
                snapshotDirty = false;
            }
            return cachedSnapshot;
        }
        if (!snapshotDirty && !cacheExpired(now)) {
            return cachedSnapshot;
        }
        // The cache expires exactly at the earliest activity expiry, so reaching this point always means
        // "recompute": either an event changed the table or at least one activity has just expired.
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
            cachedSnapshot = new PresenceActivitySnapshot(PresencePrimaryState.IDLE, listening, "", now);
        } else {
            cachedSnapshot = new PresenceActivitySnapshot(
                    primaryState(primary.key().activityType()),
                    listening,
                    primary.key().sourceId(),
                    Math.max(now, primary.occurredAtMillis())
            );
        }
        cachedSnapshotExpiryAtMillis = nextExpiryAtMillis();
        snapshotDirty = false;
        return cachedSnapshot;
    }

    private boolean cacheExpired(long nowMillis) {
        return cachedSnapshotExpiryAtMillis > 0L && nowMillis >= cachedSnapshotExpiryAtMillis;
    }

    private long nextExpiryAtMillis() {
        long next = 0L;
        for (ActiveActivity activity : activities.values()) {
            long expiry = activity.expiresAtMillis();
            if (expiry > 0L && (next == 0L || expiry < next)) {
                next = expiry;
            }
        }
        return next;
    }

    /**
     * Whether the publisher owns this activity type. The rule comes from the contract, so this aggregator
     * never names a feature module: {@code LOADING} and {@code PROCESSING_TASK} are open to everyone,
     * everything else belongs to exactly one module.
     */
    private boolean sourceAllowed(String sourceId, PresenceActivityType type) {
        String owner = type.publisherModuleId();
        return owner.isEmpty() || owner.equals(sourceId);
    }

    private boolean higherPriority(ActiveActivity candidate, ActiveActivity current) {
        int candidatePriority = candidate.key().activityType().priority();
        int currentPriority = current.key().activityType().priority();
        if (candidatePriority != currentPriority) {
            return candidatePriority > currentPriority;
        }
        return candidate.occurredAtMillis() >= current.occurredAtMillis();
    }

    private PresencePrimaryState primaryState(PresenceActivityType type) {
        return switch (type) {
            case LOADING -> PresencePrimaryState.LOADING;
            case PROCESSING_TASK -> PresencePrimaryState.PROCESSING_TASK;
            case THINKING -> PresencePrimaryState.THINKING;
            case RESPONDING -> PresencePrimaryState.RESPONDING;
            case RECONNECTING -> PresencePrimaryState.RECONNECTING;
            case ERROR -> PresencePrimaryState.ERROR;
            case DEGRADED -> PresencePrimaryState.DEGRADED;
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
