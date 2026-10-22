package com.rheinmetal.tianshu.function.auxilium.module.gamecontext;

import com.rheinmetal.tianshu.protocol.PresenceContextFactIds;

import java.util.List;

public final class AXPresenceFactIds {
    public static final List<String> DEFAULT_DYNAMIC_FACTS = List.of(
            PresenceContextFactIds.INTERACTION_CONTEXT,
            PresenceContextFactIds.PLAYER_STATUS,
            PresenceContextFactIds.PLAYER_INVENTORY,
            PresenceContextFactIds.PLAYER_ACTIVE_EFFECTS,
            PresenceContextFactIds.WORLD_ENVIRONMENT
    );

    private AXPresenceFactIds() {
    }
}
