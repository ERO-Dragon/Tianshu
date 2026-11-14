package com.rheinmetal.tianshu.api.game;

/** What one transfer moved, or why it moved nothing. */
public record ContainerTransferResult(String itemId, int moved, String message) {
}
