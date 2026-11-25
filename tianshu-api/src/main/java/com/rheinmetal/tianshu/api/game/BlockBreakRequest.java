package com.rheinmetal.tianshu.api.game;

/**
 * Which block to break, as absolute coordinates.
 *
 * <p>No face and no tool argument: breaking is not directed at a face, and the actor's own hand decides what it can dig —
 * exactly like a player swinging at a block.</p>
 */
public record BlockBreakRequest(int x, int y, int z) {
}