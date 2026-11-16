package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.entity.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Turns "which side of this block" into a direction, deriving it from the actor when no face was requested. */
final class Faces {

    private Faces() {
    }

    static Direction of(BlockFace face, Vec3 from, BlockPos pos) {
        if (face != null) {
            return switch (face) {
                case UP -> Direction.UP;
                case DOWN -> Direction.DOWN;
                case NORTH -> Direction.NORTH;
                case SOUTH -> Direction.SOUTH;
                case EAST -> Direction.EAST;
                case WEST -> Direction.WEST;
            };
        }
        Vec3 delta = from.subtract(Vec3.atCenterOf(pos));
        double ax = Math.abs(delta.x);
        double ay = Math.abs(delta.y);
        double az = Math.abs(delta.z);
        if (ax >= ay && ax >= az && ax > 1.0E-4) {
            return delta.x > 0 ? Direction.EAST : Direction.WEST;
        }
        if (ay >= az && ay > 1.0E-4) {
            return delta.y > 0 ? Direction.UP : Direction.DOWN;
        }
        if (az > 1.0E-4) {
            return delta.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return Direction.UP;
    }
}