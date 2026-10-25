package com.rheinmetal.tianshu.api.game;

/**
 * One reading of the player's situation, taken on the client thread.
 *
 * <p>The platform can see health, food, dimension, biome and the death message; what counts as an event
 * worth reporting is a module's decision. This record is deliberately dull: numbers and ids, no meaning.</p>
 */
public record GameEventFacts(
        int health,
        int foodLevel,
        String dimensionId,
        String biomeId,
        boolean dead,
        String deathMessage,
        long atMillis
) {

    public GameEventFacts {
        dimensionId = dimensionId == null ? "" : dimensionId;
        biomeId = biomeId == null ? "" : biomeId;
        deathMessage = deathMessage == null ? "" : deathMessage;
    }
}
