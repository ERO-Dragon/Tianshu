package com.rheinmetal.tianshu.api.tool;

/**
 * What a tool does and where it has to run.
 *
 * <p>The category decides the default policy: read-only capabilities may be on by default, while anything that
 * changes the world starts disabled and needs an explicit opt-in. See {@link ToolPolicy}.</p>
 */
public enum ToolCategory {

    /** Pure client-side query; no world change and no server round trip. */
    LOCAL,

    /** Read-only query that only the authoritative server can answer. */
    SERVER_READ,

    /** A world change, executed by the server component. Disabled by default. */
    SERVER_WRITE,

    /**
     * A world change an ordinary player could do by hand: craft here, move these items, place this block, press that
     * button.
     *
     * <p>The distinction from {@link #SERVER_WRITE} is who has to be allowed to do it, not where it runs: no operator,
     * because any survival player may craft or open a chest. Disabled by default like every world change, so an
     * operator still opts in per server; the player's own command rights and the server's claim rules bound it after
     * that.</p>
     */
    PLAYER_WRITE,

    /**
     * A body's own action: the entity moves, mines or places on its own behalf.
     *
     * <p>Disabled by default like any world change, but no operator is required: who may command the body is decided
     * by ownership, not by the caller's permission. When the body is player-like, the server's own permission system
     * bounds it further, because that entity is a player.</p>
     */
    BODY_WRITE;

    public boolean isServerSide() {
        return this != LOCAL;
    }
}