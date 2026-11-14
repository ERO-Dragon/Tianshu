package com.rheinmetal.tianshu.api.game;

/**
 * Move items between one container slot and one player inventory slot.
 *
 * @param x             the container block's x coordinate
 * @param y             the container block's y coordinate
 * @param z             the container block's z coordinate
 * @param containerSlot the slot inside the container
 * @param playerSlot    the slot inside the player's main inventory (0..35)
 * @param count         how many items to move
 * @param toPlayer      true moves container to player, false moves player to container
 */
public record ContainerTransferRequest(int x, int y, int z, int containerSlot, int playerSlot, int count,
                                       boolean toPlayer) {
}
