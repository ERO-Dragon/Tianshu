package com.rheinmetal.tianshu.function.presence.protocol;

import java.util.List;

public final class PresenceContextFactIds {
    public static final String PLAYER_STATUS = "presence.player.status";
    public static final String WORLD_ENVIRONMENT = "presence.world.environment";
    public static final String PLAYER_INVENTORY = "presence.player.inventory";
    public static final String PLAYER_EQUIPMENT = "presence.player.equipment";
    public static final String PLAYER_ACTIVE_EFFECTS = "presence.player.active_effects";
    public static final String INTERACTION_CONTEXT = "presence.interaction.context";

    /**
     * Every fact id this build can produce.
     *
     * <p>A consumer that wants everything asks for this list, so a fact added here is requested without that
     * consumer changing: the request says "whatever you have", and what to make of an answer is the consumer's
     * own business.</p>
     */
    public static final List<String> ALL = List.of(
            INTERACTION_CONTEXT,
            PLAYER_STATUS,
            PLAYER_EQUIPMENT,
            PLAYER_INVENTORY,
            PLAYER_ACTIVE_EFFECTS,
            WORLD_ENVIRONMENT
    );

    private PresenceContextFactIds() {
    }
}
