package com.rheinmetal.tianshu.fabric;

import com.mojang.authlib.GameProfile;
import com.rheinmetal.tianshu.mc.body.ActionPlayerFactory;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** The Fabric facade: the per-level {@link FakePlayer} from Fabric API, repositioned for each call. */
public final class FabricActionPlayers implements ActionPlayerFactory {

    private static final GameProfile PROFILE = new GameProfile(
            UUID.nameUUIDFromBytes("tianshu:action".getBytes(StandardCharsets.UTF_8)), "tianshu_action");

    @Override
    public Player create(ServerLevel level, Vec3 position, float yaw, float pitch) {
        FakePlayer player = FakePlayer.get(level, PROFILE);
        player.moveTo(position.x, position.y, position.z, yaw, pitch);
        return player;
    }
}