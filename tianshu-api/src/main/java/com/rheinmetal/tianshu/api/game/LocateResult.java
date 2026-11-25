package com.rheinmetal.tianshu.api.game;

/**
 * The nearest match of a structure or biome search.
 *
 * <p>{@code found == false} is a real answer, not an error: "there is no desert within 256 blocks" is information the
 * caller needs, and it is different from "that biome id does not exist", which is a refusal. {@code sampled} says how
 * much of the area was actually looked at, because a biome search is a sampling and a caller should not read a bounded
 * sample as an exhaustive sweep.</p>
 */
public record LocateResult(
        String kind,
        String targetId,
        boolean found,
        int x,
        int y,
        int z,
        double distanceBlocks,
        int sampled,
        String message) {

    public LocateResult {
        kind = kind == null ? "" : kind;
        targetId = targetId == null ? "" : targetId;
        message = message == null ? "" : message;
    }

    /** A refusal: nothing was searched, and the message says why. */
    public static LocateResult failure(String message) {
        return new LocateResult("", "", false, 0, 0, 0, 0.0D, 0, message);
    }

    /** A search that ran and found nothing in range. */
    public static LocateResult notFound(String kind, String targetId, int sampled, String message) {
        return new LocateResult(kind, targetId, false, 0, 0, 0, 0.0D, sampled, message);
    }

    /** True when the search itself ran, whether or not it found anything. */
    public boolean searched() {
        return !kind.isBlank();
    }
}