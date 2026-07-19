package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.client.presence.context.PresenceContextGroup;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceStatusSnapshot;
import com.rheinmetal.tianshu.client.presence.status.PresenceStatusPriority;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class PresenceStateStore {
    private final Object lock = new Object();
    private PresenceContextSnapshot contextSnapshot = PresenceContextSnapshot.empty();
    private final Map<String, PresenceStatusSnapshot> statusBySource = new LinkedHashMap<>();
    private final EnumSet<PresenceContextGroup> availableGroups = EnumSet.noneOf(PresenceContextGroup.class);
    private final EnumSet<PresenceContextGroup> dirtyGroups = EnumSet.noneOf(PresenceContextGroup.class);
    private boolean worldSessionActive = true;

    public void updateContext(PresenceContextSnapshot snapshot) {
        updateGroups(snapshot, EnumSet.of(PresenceContextGroup.INTERACTION_CONTEXT));
    }

    public PresenceContextSnapshot contextSnapshot() {
        synchronized (lock) {
            return contextSnapshot;
        }
    }

    public void startWorldSession() {
        synchronized (lock) {
            resetWorldStateLocked();
            worldSessionActive = true;
        }
    }

    public void resetWorldState() {
        synchronized (lock) {
            resetWorldStateLocked();
            worldSessionActive = false;
        }
    }

    public boolean worldSessionActive() {
        synchronized (lock) {
            return worldSessionActive;
        }
    }

    public void updateGroups(PresenceContextSnapshot snapshot, Set<PresenceContextGroup> groups) {
        if (snapshot == null || groups == null || groups.isEmpty()) {
            return;
        }
        synchronized (lock) {
            if (!worldSessionActive) {
                return;
            }
            EnumSet<PresenceContextGroup> effectiveGroups = PresenceContextGroup.copyOf(groups);
            contextSnapshot = merge(contextSnapshot, snapshot, effectiveGroups);
            availableGroups.addAll(effectiveGroups);
            dirtyGroups.removeAll(effectiveGroups);
        }
    }

    public void markDirty(PresenceContextGroup group) {
        if (group == null) {
            return;
        }
        synchronized (lock) {
            dirtyGroups.add(group);
        }
    }

    public EnumSet<PresenceContextGroup> groupsNeedingRefresh(Set<PresenceContextGroup> requestedGroups) {
        if (requestedGroups == null || requestedGroups.isEmpty()) {
            return EnumSet.noneOf(PresenceContextGroup.class);
        }
        synchronized (lock) {
            EnumSet<PresenceContextGroup> result = EnumSet.noneOf(PresenceContextGroup.class);
            for (PresenceContextGroup group : requestedGroups) {
                if (group != PresenceContextGroup.INTERACTION_CONTEXT
                        || !availableGroups.contains(group)
                        || dirtyGroups.contains(group)) {
                    result.add(group);
                }
            }
            return result;
        }
    }

    public void updateStatus(PresenceStatusSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        synchronized (lock) {
            if (!worldSessionActive) {
                return;
            }
            String source = snapshot.sourceModuleId();
            PresenceStatusSnapshot current = statusBySource.get(source);
            if (current != null && snapshot.updatedAtMillis() < current.updatedAtMillis()) {
                return;
            }
            if (snapshot.statusType() == com.rheinmetal.tianshu.client.presence.model.PresenceStatusType.IDLE) {
                if (current == null || sameActivity(current, snapshot)) {
                    statusBySource.remove(source);
                }
                return;
            }
            statusBySource.put(source, snapshot);
        }
    }

    public PresenceStatusSnapshot statusSnapshot() {
        return statusSnapshot(source -> true);
    }

    public PresenceStatusSnapshot statusSnapshot(Predicate<String> sourceVisible) {
        Predicate<String> visible = sourceVisible == null ? source -> true : sourceVisible;
        synchronized (lock) {
            long now = System.currentTimeMillis();
            PresenceStatusSnapshot selected = null;
            java.util.Iterator<Map.Entry<String, PresenceStatusSnapshot>> iterator = statusBySource.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, PresenceStatusSnapshot> entry = iterator.next();
                PresenceStatusSnapshot candidate = entry.getValue();
                if (candidate == null || candidate.expired(now)) {
                    iterator.remove();
                    continue;
                }
                if (visible.test(entry.getKey())
                        && (selected == null || PresenceStatusPriority.shouldReplace(selected, candidate, now))) {
                    selected = candidate;
                }
            }
            return selected == null ? PresenceStatusSnapshot.idle() : selected;
        }
    }

    private PresenceContextSnapshot merge(
            PresenceContextSnapshot base,
            PresenceContextSnapshot captured,
            Set<PresenceContextGroup> groups
    ) {
        PresenceContextSnapshot effectiveBase = base == null ? PresenceContextSnapshot.empty() : base;
        PresenceContextSnapshot effectiveCaptured = captured == null ? PresenceContextSnapshot.empty() : captured;
        boolean live = groups.contains(PresenceContextGroup.INTERACTION_CONTEXT);
        boolean playerStatus = groups.contains(PresenceContextGroup.PLAYER_STATUS);
        boolean worldEnvironment = groups.contains(PresenceContextGroup.WORLD_ENVIRONMENT);
        boolean inventory = groups.contains(PresenceContextGroup.PLAYER_INVENTORY);
        boolean effects = groups.contains(PresenceContextGroup.PLAYER_ACTIVE_EFFECTS);
        boolean capturedCurrentWorld = !groups.isEmpty();

        return new PresenceContextSnapshot(
                capturedCurrentWorld ? effectiveCaptured.playerId() : effectiveBase.playerId(),
                capturedCurrentWorld ? effectiveCaptured.dimensionId() : effectiveBase.dimensionId(),
                live ? effectiveCaptured.screenKind() : effectiveBase.screenKind(),
                live ? effectiveCaptured.containerKind() : effectiveBase.containerKind(),
                live ? effectiveCaptured.heldItemId() : effectiveBase.heldItemId(),
                live ? effectiveCaptured.equippedItemIds() : effectiveBase.equippedItemIds(),
                live ? effectiveCaptured.crosshairTarget() : effectiveBase.crosshairTarget(),
                live ? effectiveCaptured.interactionKeyDown() : effectiveBase.interactionKeyDown(),
                live ? effectiveCaptured.attackKeyDown() : effectiveBase.attackKeyDown(),
                live ? effectiveCaptured.sneaking() : effectiveBase.sneaking(),
                live ? effectiveCaptured.recentInputKind() : effectiveBase.recentInputKind(),
                playerStatus ? effectiveCaptured.playerStatus() : effectiveBase.playerStatus(),
                worldEnvironment ? effectiveCaptured.worldEnvironment() : effectiveBase.worldEnvironment(),
                inventory ? effectiveCaptured.inventoryItems() : effectiveBase.inventoryItems(),
                effects ? effectiveCaptured.activeEffects() : effectiveBase.activeEffects(),
                live ? effectiveCaptured.facts() : effectiveBase.facts(),
                Math.max(effectiveBase.capturedAtMillis(), effectiveCaptured.capturedAtMillis())
        );
    }

    private void resetWorldStateLocked() {
        contextSnapshot = PresenceContextSnapshot.empty();
        statusBySource.clear();
        availableGroups.clear();
        dirtyGroups.clear();
    }

    private boolean sameActivity(PresenceStatusSnapshot current, PresenceStatusSnapshot candidate) {
        String currentId = activityId(current);
        String candidateId = activityId(candidate);
        return currentId.isBlank() || candidateId.isBlank() || currentId.equals(candidateId);
    }

    private String activityId(PresenceStatusSnapshot snapshot) {
        if (snapshot == null) {
            return "";
        }
        Map<String, String> attributes = snapshot.attributes();
        String sessionId = attributes.getOrDefault("sessionId", "");
        if (!sessionId.isBlank()) {
            return "session:" + sessionId;
        }
        String taskId = attributes.getOrDefault("taskId", "");
        if (!taskId.isBlank()) {
            return "task:" + taskId;
        }
        return "";
    }
}
