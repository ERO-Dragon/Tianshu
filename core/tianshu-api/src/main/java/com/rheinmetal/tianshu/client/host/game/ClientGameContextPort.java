package com.rheinmetal.tianshu.client.host.game;

import java.util.List;

/**
 * The platform's game reading, exposed to whichever module wants to look at the world.
 *
 * <p>This is deliberately grouped: a caller asks only for the slice it needs, and the implementation only reads
 * that slice. One full capture per call would make a module that only watches the crosshair iterate the inventory
 * and the effect list twenty times a second; the methods keep the cost proportional to the question.</p>
 *
 * <p>Every method is called on the client thread and returns values, never live game objects. A failing read must
 * degrade to an empty value rather than throw: this is the platform side of a module's feature, not a game loop.</p>
 */
public interface ClientGameContextPort {
    /** The local player's id, or blank when no world session is running. */
    String playerId();

    /** Dimension, screen, held and worn items, crosshair target and input flags — the per-tick slice. */
    GameInteractionContext captureInteraction();

    GamePlayerStatus capturePlayerStatus();

    List<GameInventoryEntry> captureInventory();

    GameEquipment captureEquipment();

    List<GameEffect> captureEffects();

    GameWorldEnvironment captureEnvironment();

    /** The block under the crosshair; cheap, O(1). */
    GameBlockTarget captureBlockTarget();

    /** Position, rotation and game mode; cheap, O(1). */
    GamePlayerPose capturePlayerPose();

    /** Experience progress, saturation and absorption; cheap, O(1). */
    GamePlayerVitals captureVitals();

    /**
     * The container the player currently has open, if any.
     *
     * <p>Only reads the open menu (bounded by vanilla's slot count); outside a container screen it returns
     * {@link GameContainerView#EMPTY} without touching the world.</p>
     */
    GameContainerView captureOpenContainer();

    /**
     * The public tab list: names, latency and game mode.
     *
     * <p>O(players) and reads only fields the client already renders; never another player's inventory.</p>
     */
    List<GamePlayerListEntry> capturePlayerList();
}