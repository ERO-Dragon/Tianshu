package com.rheinmetal.tianshu.mc.body;

import net.minecraft.world.entity.player.Player;

/**
 * Who acts when a vanilla player-typed API needs a player for a body.
 *
 * <p>Two strategies, chosen once when the body is created — never per call, so no action source ever branches on
 * identity:</p>
 *
 * <ul>
 *   <li><b>borrowed</b> — the body has no player identity: a short-lived facade is created at the body, the body's
 *       inventory is copied into it, and the result is copied back. This is the existing behaviour.</li>
 *   <li><b>player-backed</b> — the body has its own long-lived player: that player acts directly, its inventory is the
 *       body's inventory, and there is nothing to copy.</li>
 * </ul>
 */
public final class BodyIdentity {

    private final ActionPlayerFactory borrowed;

    private BodyIdentity(ActionPlayerFactory borrowed) {
        this.borrowed = borrowed;
    }

    /** A body with no player identity: each call borrows a facade. */
    public static BodyIdentity borrowed(ActionPlayerFactory factory) {
        return new BodyIdentity(java.util.Objects.requireNonNull(factory, "factory"));
    }

    /** A body with its own player identity: that player acts for it, directly. */
    public static BodyIdentity playerBacked() {
        return new BodyIdentity(null);
    }

    /** Whether this body takes part in player rules; the outward identity {@code EntityActor.playerLike()} reports. */
    public boolean playerLike() {
        return borrowed == null;
    }

    /** Whether the acting player is a copy that must be committed and discarded. */
    public boolean borrowed() {
        return borrowed != null;
    }

    /** The player to hand to a vanilla API for {@code body}. */
    public Player player(BodyRegistry.BodyState body) {
        if (playerLike()) {
            return (Player) body.entity();
        }
        return borrowed.create(body.level(), body.position(),
                body.entity().getYRot(), body.entity().getXRot());
    }
}