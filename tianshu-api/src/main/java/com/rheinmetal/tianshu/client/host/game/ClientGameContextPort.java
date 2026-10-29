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
}