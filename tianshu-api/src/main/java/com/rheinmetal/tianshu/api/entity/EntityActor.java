package com.rheinmetal.tianshu.api.entity;

import java.util.Set;

/**
 * A server-side entity that can act as the agent's body. The platform only knows its identity, permission,
 * position and what it can do; inventory and game actions stay behind the game-side ports.
 *
 * <p>A body is not assumed to be humanoid or alive in the player sense: it may float, have no health and no
 * hands. Capabilities say what it can do, and a missing capability is a refusal rather than a failure.</p>
 */
public interface EntityActor {

    String id();

    boolean operator();

    /**
     * Whether this entity is player-like: it takes part in player rules and inherits the server's player permissions.
     *
     * <p>This is an identity, not an appearance. A provider that returns {@code true} expects the adapter to give the
     * entity a player-capable base (so claims, player-related logic and player permissions apply); {@code false} means
     * an ordinary entity whose reach is bounded by the module that registered it and by the platform's switches.</p>
     */
    default boolean playerLike() {
        return false;
    }

    ActorPosition position();

    Set<EntityCapability> capabilities();
}