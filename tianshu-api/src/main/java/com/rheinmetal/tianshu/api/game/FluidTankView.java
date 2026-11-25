package com.rheinmetal.tianshu.api.game;

/**
 * One tank of a block's fluid storage.
 *
 * <p>Amounts are millibuckets, which is what every loader's fluid API counts in. A tank that is empty is not reported:
 * "which tanks exist" is a property of the machine, and reporting empty ones filled every answer with noise on a
 * machine that has a dozen tanks and one of them in use.</p>
 */
public record FluidTankView(int tank, String fluidId, long millibuckets, long capacity) {
}