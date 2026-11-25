package com.rheinmetal.tianshu.api.game;

/**
 * Where to look, as block coordinates.
 *
 * <p>Absolute coordinates, like every other game-side request: the caller converts from whatever frame it has.</p>
 */
public record BlockInspectRequest(int x, int y, int z) {
}