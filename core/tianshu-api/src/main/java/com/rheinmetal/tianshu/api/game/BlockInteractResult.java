package com.rheinmetal.tianshu.api.game;

/**
 * What happened to one block interaction.
 *
 * @param blockId   the block actually found, or an empty string when there was none
 * @param interacted whether the block behaved as an interaction (button pressed, door opened, ...)
 * @param message   the game's own outcome text, for the agent to relay
 */
public record BlockInteractResult(String blockId, boolean interacted, String message) {
}
