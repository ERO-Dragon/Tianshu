package com.rheinmetal.tianshu.api.game;

/**
 * One machine operation on the block at a position.
 *
 * @param x          the machine block's x coordinate
 * @param y          the machine block's y coordinate
 * @param z          the machine block's z coordinate
 * @param operation  {@code insert_fuel}, {@code insert_input} or {@code take_output}
 * @param playerSlot the player inventory slot used by the insert operations; ignored by {@code take_output}
 */
public record MachineOpRequest(int x, int y, int z, String operation, int playerSlot) {
}
