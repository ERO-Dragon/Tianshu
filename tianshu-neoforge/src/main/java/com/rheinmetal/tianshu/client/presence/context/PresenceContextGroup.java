package com.rheinmetal.tianshu.client.presence.context;

import com.rheinmetal.tianshu.protocol.PresenceContextFactIds;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public enum PresenceContextGroup {
    INTERACTION_CONTEXT(PresenceContextFactIds.INTERACTION_CONTEXT),
    PLAYER_STATUS(PresenceContextFactIds.PLAYER_STATUS),
    PLAYER_INVENTORY(PresenceContextFactIds.PLAYER_INVENTORY),
    PLAYER_ACTIVE_EFFECTS(PresenceContextFactIds.PLAYER_ACTIVE_EFFECTS),
    WORLD_ENVIRONMENT(PresenceContextFactIds.WORLD_ENVIRONMENT);

    private final String factId;

    PresenceContextGroup(String factId) {
        this.factId = factId;
    }

    public String factId() {
        return factId;
    }

    public static EnumSet<PresenceContextGroup> fromFactIds(List<String> requestedFactIds) {
        List<String> effective = requestedFactIds == null || requestedFactIds.isEmpty()
                ? PresenceContextFactIds.AX_PROMPT_DEFAULTS
                : requestedFactIds;
        EnumSet<PresenceContextGroup> groups = EnumSet.noneOf(PresenceContextGroup.class);
        for (String factId : effective) {
            PresenceContextGroup group = fromFactId(factId);
            if (group != null) {
                groups.add(group);
            }
        }
        return groups;
    }

    public static EnumSet<PresenceContextGroup> details() {
        return EnumSet.of(PLAYER_STATUS, PLAYER_INVENTORY, PLAYER_ACTIVE_EFFECTS, WORLD_ENVIRONMENT);
    }

    public static EnumSet<PresenceContextGroup> copyOf(Set<PresenceContextGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return EnumSet.noneOf(PresenceContextGroup.class);
        }
        return EnumSet.copyOf(groups);
    }

    private static PresenceContextGroup fromFactId(String factId) {
        if (factId == null || factId.isBlank()) {
            return null;
        }
        String normalized = factId.trim();
        for (PresenceContextGroup group : values()) {
            if (group.factId.equals(normalized)) {
                return group;
            }
        }
        return null;
    }
}
