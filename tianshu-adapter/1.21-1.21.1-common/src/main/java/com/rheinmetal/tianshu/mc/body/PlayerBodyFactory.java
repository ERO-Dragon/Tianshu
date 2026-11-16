package com.rheinmetal.tianshu.mc.body;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Creates the long-lived player a {@linkplain BodyIdentity#playerBacked player-backed} body acts through.
 *
 * <p>Unlike {@link ActionPlayerFactory}, whose contract forbids an instance from outliving one call, this player lives
 * as long as its body: the server treats it as a player, so player permissions, claim rules and player events apply to
 * everything the body does. That is the whole point of the identity, and the reason it cannot be borrowed per call.</p>
 *
 * <p>Returning {@code null} means this adapter cannot create such a player at all (Fabric today). The caller reports
 * that as a refusal rather than silently downgrading the body to an ordinary one.</p>
 */
public interface PlayerBodyFactory {

    /**
     * @param bodyId the body's id, also the player's identity; two bodies never share one
     * @return the player, or {@code null} when this side cannot create player-backed bodies
     */
    ServerPlayer create(ServerLevel level, UUID bodyId);
}