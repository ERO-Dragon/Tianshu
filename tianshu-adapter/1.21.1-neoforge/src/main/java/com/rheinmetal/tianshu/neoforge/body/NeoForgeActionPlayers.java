package com.rheinmetal.tianshu.neoforge.body;

import com.mojang.authlib.GameProfile;
import com.rheinmetal.tianshu.mc.body.ActionPlayerFactory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** The NeoForge facade: a fresh {@link FakePlayer} per call, positioned at the body. */
public final class NeoForgeActionPlayers implements ActionPlayerFactory {

    private static final GameProfile PROFILE = new GameProfile(
            UUID.nameUUIDFromBytes("tianshu:action".getBytes(StandardCharsets.UTF_8)), "tianshu_action");

    @Override
    public Player create(ServerLevel level, Vec3 position, float yaw, float pitch) {
        FakePlayer player = new FakePlayer(level, PROFILE);
        player.moveTo(position.x, position.y, position.z, yaw, pitch);
        return player;
    }
}