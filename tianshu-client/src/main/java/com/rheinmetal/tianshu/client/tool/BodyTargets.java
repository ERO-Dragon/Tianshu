package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.entity.ActorPosition;
import com.rheinmetal.tianshu.api.entity.BlockTarget;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

/**
 * Builds the two position value types, translating their range check into a tool error.
 *
 * <p>{@link BlockTarget} and {@link ActorPosition} reject coordinates beyond the world border, which is the right place
 * for the check — one value type, every tool inherits it. What they cannot do is speak the tool contract, so an
 * out-of-range coordinate escaped as an {@code IllegalArgumentException} instead of the {@code INVALID_ARGS} a model
 * can act on. Both translations live here so a tool never repeats the {@code catch}.</p>
 */
final class BodyTargets {

    private BodyTargets() {
    }

    /**
     * The dimension a tool acts in: the body's own, unless the caller named one.
     *
     * <p>A name is checked, never obeyed. Bodies do not cross dimensions, so a request that names another one is
     * refused with both dimensions in the message — that is the whole point of accepting the parameter. Without it a
     * caller that believed it was in the nether would silently act on the overworld; the coordinates are in range
     * either way, so nothing else can catch that.</p>
     */
    static String dimension(String requested, String current) {
        if (requested == null || requested.isBlank()) {
            return current;
        }
        String named = requested.trim();
        if (named.equals(current)) {
            return current;
        }
        throw new ToolException(ToolError.INVALID_ARGS,
                "the body is in " + current + "; a request that names " + named
                        + " is refused, because bodies do not cross dimensions");
    }

    static BlockTarget block(String dimension, int x, int y, int z) {
        try {
            return new BlockTarget(dimension, x, y, z);
        } catch (IllegalArgumentException outOfRange) {
            throw new ToolException(ToolError.INVALID_ARGS, outOfRange.getMessage());
        }
    }

    static ActorPosition position(String dimension, double x, double y, double z) {
        try {
            return new ActorPosition(dimension, x, y, z);
        } catch (IllegalArgumentException outOfRange) {
            throw new ToolException(ToolError.INVALID_ARGS, outOfRange.getMessage());
        }
    }
}