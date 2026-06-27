package com.rheinmetal.tianshu.protocol;

import java.util.List;

public final class PresenceContextFactIds {
    public static final String PLAYER_STATUS = "presence.player.status";
    public static final String WORLD_ENVIRONMENT = "presence.world.environment";
    public static final String PLAYER_INVENTORY = "presence.player.inventory";
    public static final String PLAYER_ACTIVE_EFFECTS = "presence.player.active_effects";
    public static final String CHAT_RECENT = "presence.chat.recent";
    public static final String INTERACTION_CONTEXT = "presence.interaction.context";
    public static final String INTERACTION_RECENT_EVENTS = "presence.interaction.recent_events";

    public static final List<String> AX_PROMPT_DEFAULTS = List.of(
            INTERACTION_CONTEXT,
            PLAYER_STATUS,
            PLAYER_INVENTORY,
            PLAYER_ACTIVE_EFFECTS,
            WORLD_ENVIRONMENT,
            CHAT_RECENT,
            INTERACTION_RECENT_EVENTS
    );

    private PresenceContextFactIds() {
    }
}
