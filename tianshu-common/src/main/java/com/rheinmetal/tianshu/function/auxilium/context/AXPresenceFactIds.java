package com.rheinmetal.tianshu.function.auxilium.context;

import com.rheinmetal.tianshu.protocol.PresenceContextFactIds;

import java.util.List;

public final class AXPresenceFactIds {
    public static final List<String> DEFAULT_CONTEXT_FACTS = List.of(
            PresenceContextFactIds.INTERACTION_CONTEXT,
            PresenceContextFactIds.PLAYER_STATUS,
            PresenceContextFactIds.PLAYER_INVENTORY,
            PresenceContextFactIds.PLAYER_ACTIVE_EFFECTS,
            PresenceContextFactIds.WORLD_ENVIRONMENT
    );

    private AXPresenceFactIds() {
    }
}
