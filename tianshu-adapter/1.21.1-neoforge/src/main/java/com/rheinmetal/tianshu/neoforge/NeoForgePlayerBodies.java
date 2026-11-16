package com.rheinmetal.tianshu.neoforge;

import com.mojang.authlib.GameProfile;
import com.rheinmetal.tianshu.mc.body.PlayerBodyFactory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.util.UUID;

/**
 * Player-backed bodies on NeoForge: one long-lived {@link FakePlayer} per body.
 *
 * <p>Deliberately not {@link com.rheinmetal.tianshu.mc.body.ActionPlayerFactory}: that contract forbids an instance
 * from outliving one call, while a body's player is its identity for as long as the body exists. Because it is a
 * {@code ServerPlayer}, the server's own player rules — permissions, claim checks, player events — apply to whatever
 * the body does through it.</p>
 *
 * <p>The profile is derived from the body id, so a body always comes back as the same player and two bodies can never
 * share one.</p>
 */
public final class NeoForgePlayerBodies implements PlayerBodyFactory {

    @Override
    public ServerPlayer create(ServerLevel level, UUID bodyId) {
        GameProfile profile = new GameProfile(bodyId, nameOf(bodyId));
        FakePlayer player = new FakePlayer(level, profile);
        player.setPos(0, 0, 0);
        return player;
    }

    private static String nameOf(UUID bodyId) {
        return "tianshu_body_" + bodyId.toString().replace("-", "").substring(0, 12);
    }
}