package com.rheinmetal.tianshu.function.presence.status;

import com.rheinmetal.tianshu.client.presence.model.PresenceActivitySnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresencePrimaryState;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityAction;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityType;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Turns the stream of module activities into the one thing the HUD draws: a primary state plus parallel marks.
 *
 * <p>Primary activities (thinking, responding, error, reconnecting, loading) compete by the priority declared on
 * the activity type; parallel activities (listening, background work) never compete, they only flip a boolean.
 * The result is cached and recomputed only when an activity changed or the earliest activity expired, so the render
 * thread reads an immutable snapshot instead of scanning a table.</p>
 */
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
        if (!worldSessionActive || source.isEmpty() || payload == null) {
            return;
        }
        PresenceActivityType type = payload.activityType();
        ActivityKey key = new ActivityKey(source, payload.activityId(), type);
        if (payload.action() == PresenceActivityAction.ENDED) {
            // A turn-scoped activity is closed by whoever resolves the turn, not necessarily by the module that
            // opened it, so its id and type are what identify it here.
            if (endListeningByAnyPublisher(type, payload.activityId())) {
                snapshotDirty = true;
                return;
            }
            if (!sourceAllowed(source, type)) {
                return;
            }
            activities.remove(key);
            snapshotDirty = true;
            return;
        }
        if (!sourceAllowed(source, type) || payload.occurredAtMillis() < worldSessionStartedAtMillis) {
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
        boolean listening = false;
        boolean backgroundTask = false;
        ActiveActivity primary = null;
        for (ActiveActivity activity : activities.values()) {
            PresenceActivityType type = activity.key().activityType();
            if (type == PresenceActivityType.LISTENING) {
                listening = true;
                continue;
            }
            if (!type.primary()) {
                backgroundTask = true;
                continue;
            }
            if (primary == null || higherPriority(activity, primary)) {
                primary = activity;
            }
        }
        if (primary == null) {
            cachedSnapshot = new PresenceActivitySnapshot(PresencePrimaryState.IDLE, listening, backgroundTask, "", now);
        } else {
            cachedSnapshot = new PresenceActivitySnapshot(
                    primaryState(primary.key().activityType()),
                    listening,
                    backgroundTask,
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
     * Removes a turn-scoped activity by identity, whichever module ended it.
     *
     * @return true when this end was handled here
     */
    private boolean endListeningByAnyPublisher(PresenceActivityType type, String activityId) {
        if (!type.endableByAnyModule()) {
            return false;
        }
        Iterator<Map.Entry<ActivityKey, ActiveActivity>> iterator = activities.entrySet().iterator();
        while (iterator.hasNext()) {
            ActivityKey key = iterator.next().getKey();
            if (key.activityType() == type && key.activityId().equals(activityId)) {
                iterator.remove();
                return true;
            }
        }
        // Ending something that never started is not an error: the opener may have been torn down first.
        return true;
    }

    /**
     * Whether the publisher owns this activity type. The rule comes from the contract, so this aggregator
     * never names a feature module: an empty owner means any module may publish it.
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
            case THINKING -> PresencePrimaryState.THINKING;
            case RESPONDING -> PresencePrimaryState.RESPONDING;
            case RECONNECTING -> PresencePrimaryState.RECONNECTING;
            case ERROR -> PresencePrimaryState.ERROR;
            case PROCESSING_TASK, LISTENING -> PresencePrimaryState.IDLE;
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