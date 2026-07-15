package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.client.presence.context.PresenceContextGroup;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceStatusSnapshot;
import com.rheinmetal.tianshu.client.presence.status.PresenceStatusPriority;

import java.util.EnumSet;
import java.util.Set;

public final class PresenceStateStore {
    private final Object lock = new Object();
    private PresenceContextSnapshot contextSnapshot = PresenceContextSnapshot.empty();
    private PresenceStatusSnapshot statusSnapshot = PresenceStatusSnapshot.idle();
    private final EnumSet<PresenceContextGroup> availableGroups = EnumSet.noneOf(PresenceContextGroup.class);
    private final EnumSet<PresenceContextGroup> dirtyGroups = EnumSet.noneOf(PresenceContextGroup.class);

    public void updateContext(PresenceContextSnapshot snapshot) {
        updateGroups(snapshot, EnumSet.of(PresenceContextGroup.INTERACTION_CONTEXT));
    }

    public PresenceContextSnapshot contextSnapshot() {
        synchronized (lock) {
            return contextSnapshot;
        }
    }

    public void updateGroups(PresenceContextSnapshot snapshot, Set<PresenceContextGroup> groups) {
        if (snapshot == null || groups == null || groups.isEmpty()) {
            return;
        }
        synchronized (lock) {
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
                if (group == PresenceContextGroup.INTERACTION_CONTEXT
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

        return new PresenceContextSnapshot(
                live || worldEnvironment ? effectiveCaptured.playerId() : effectiveBase.playerId(),
                live || worldEnvironment ? effectiveCaptured.dimensionId() : effectiveBase.dimensionId(),
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
}
