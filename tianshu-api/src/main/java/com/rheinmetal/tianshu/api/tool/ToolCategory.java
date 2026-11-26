package com.rheinmetal.tianshu.api.tool;

/**
 * What a tool does and where it has to run.
 *
 * <p>The category decides two things: where the tool executes, and the risk tier it needs when it acts as a player.
 * The tier then decides the policy — a tool that stays inside survival needs no switch, only one that reaches for
 * {@link ToolAutonomy#FULL} starts switched off. See {@link ToolPolicy}.</p>
 */
public enum ToolCategory {

    /** Pure client-side query; no world change and no server round trip. */
    LOCAL,

    /** Read-only query that only the authoritative server can answer. */
    SERVER_READ,

    /** A world change, executed by the server component. Survival-tier unless the tool says it needs more. */
    SERVER_WRITE,

    /**
     * A body's own action: the entity moves, mines or places on its own behalf.
     *
     * <p>No operator and no switch: who may command the body is decided by ownership, and what it may do is what the
     * module that registered it declared. When the body is player-like, the server's own permission system bounds it
     * further, because that entity is a player.</p>
     */
    BODY_WRITE;

    /**
     * The tier a tool of this category needs when it acts as a player.
     *
     * <p>A world change is survival-tier — a player could do the same by hand. Anything that hands out items,
     * teleports or runs a command says so itself by overriding {@link TianshuTool#requiredAutonomy()}.</p>
     */
    public ToolAutonomy defaultAutonomy() {
        return this == SERVER_WRITE ? ToolAutonomy.CONSERVATIVE : ToolAutonomy.READ_ONLY;
    }

    public boolean isServerSide() {
        return this != LOCAL;
    }
}