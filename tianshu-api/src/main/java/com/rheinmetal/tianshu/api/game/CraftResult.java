package com.rheinmetal.tianshu.api.game;

/** What one craft produced, or why it did not. */
public record CraftResult(String itemId, int count, boolean crafted, String message) {
}
