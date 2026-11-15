package com.rheinmetal.tianshu.mc.body;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Builds the short-lived player a body borrows when a vanilla API insists on one.
 *
 * <p>It is a call context, not an identity: the instance is created for one placement or use, positioned at the
 * body, and discarded afterwards. Implementations must not add it to the player list, register it as an entity or
 * let it outlive the call.</p>
 */
public interface ActionPlayerFactory {

    Player create(ServerLevel level, Vec3 position, float yaw, float pitch);
}