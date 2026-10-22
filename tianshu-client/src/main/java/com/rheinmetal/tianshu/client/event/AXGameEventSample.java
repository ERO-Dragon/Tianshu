package com.rheinmetal.tianshu.client.event;

/**
 * One reading of the local player, taken by the platform hook and handed to the probe.
 *
 * <p>Plain data on purpose: the probe must stay free of Minecraft types so the crossing rules can be
 * unit tested without a game.
 *
 * @param healthPoints current health in half-heart points, {@code 0..20} for an unmodified player
 * @param foodLevel    current hunger bar value, {@code 0..20}
 * @param dimensionId  current dimension id such as {@code minecraft:the_nether}
 * @param biomeId      current biome id
 * @param dying        whether the player is currently dying or already at zero health
 */
public record AXGameEventSample(int healthPoints, int foodLevel, String dimensionId, String biomeId,
                                boolean dying) {

    public AXGameEventSample {
        dimensionId = dimensionId == null ? "" : dimensionId.trim();
        biomeId = biomeId == null ? "" : biomeId.trim();
    }
}
