package com.rheinmetal.tianshu.api.game;

/** One block interaction request: the block at an exact position, acted on as the requesting player. */
public record BlockInteractRequest(int x, int y, int z) {
}
