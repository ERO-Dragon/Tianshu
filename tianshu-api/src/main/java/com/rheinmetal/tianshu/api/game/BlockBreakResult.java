package com.rheinmetal.tianshu.api.game;

/**
 * The outcome of one break.
 *
 * <p>{@code broken == false} is the interesting half: it separates "there was nothing there", "it was out of reach" and
 * "the actor's rules refused it" (a creative player breaks instantly, a body needs the tool and the time) from the plain
 * success case. The message carries which one it was, because a caller cannot act on a bare false.</p>
 */
public record BlockBreakResult(String blockId, boolean broken, String message) {

    public BlockBreakResult {
        blockId = blockId == null ? "" : blockId;
        message = message == null ? "" : message;
    }

    /** A refusal: nothing was broken, and the message says why. */
    public static BlockBreakResult failure(String message) {
        return new BlockBreakResult("", false, message);
    }
}