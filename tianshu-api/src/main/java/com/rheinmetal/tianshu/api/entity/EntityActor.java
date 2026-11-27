package com.rheinmetal.tianshu.api.entity;

import com.rheinmetal.tianshu.api.tool.BodyAutonomy;

import java.util.Set;
import java.util.UUID;

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

    /** ID of the module that requested this NPC. An unassigned body has no module controller. */
    default String ownerModule() {
        return "";
    }

    /** UUID of the player who requested this body, or null for a server-only body. */
    default UUID ownerPlayer() {
        return null;
    }

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

    /** Tier for using a declared capability. Providers mark extra powers on this body as CHEAT. */
    default BodyAutonomy requiredAutonomy(EntityCapability capability) {
        return BodyAutonomy.SURVIVAL;
    }

    /** Concrete survival movement modes. Providers of flying or teleporting bodies override this declaration. */
    default Set<MoveMode> nativeMovementModes() {
        return capabilities().contains(EntityCapability.MOVE) ? Set.of(MoveMode.WALK) : Set.of();
    }
}