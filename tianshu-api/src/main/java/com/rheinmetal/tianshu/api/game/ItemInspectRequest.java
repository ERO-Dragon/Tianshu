package com.rheinmetal.tianshu.api.game;

/**
 * Which slot of the actor's inventory to read.
 *
 * <p>The slot is the index {@code body.inventory} reported, so a caller that has the listing can ask about one entry in
 * it without the platform having to invent a second addressing scheme.</p>
 */
public record ItemInspectRequest(int slot) {
}