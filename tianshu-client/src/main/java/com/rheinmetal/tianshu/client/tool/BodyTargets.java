package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.agent.ActorPosition;
import com.rheinmetal.tianshu.api.agent.BlockTarget;
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