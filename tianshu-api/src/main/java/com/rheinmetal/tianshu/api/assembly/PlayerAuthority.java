package com.rheinmetal.tianshu.api.assembly;

/**
 * What the server knows about a player's authority, for callers that act on a player's behalf but are not the
 * player object themselves.
 *
 * <p>The conversation runtime needs this because it holds a player id, not a player: deciding whether a write tool
 * is allowed means asking the server. Empty when no game side is installed — a caller then gets no permission.</p>
 */
public interface PlayerAuthority {

    /** True when the player is online and holds the operator permission (level 2). */
    boolean operator(String playerId);
}