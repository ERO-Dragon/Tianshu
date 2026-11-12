package com.rheinmetal.tianshu.api.tool;

/**
 * Who is asking. The same tool can be available to the conversation agent and to another mod's plain logic,
 * and a policy may treat those two differently.
 *
 * <p>{@code operator} is a property of the caller, not of the tool: a player-driven call inherits the player's
 * permission, while the agent only carries it when it is acting for such a player.</p>
 */
public record ToolCaller(Kind kind, String id, boolean operator) {

    public enum Kind {
        /** The conversation agent. */
        AGENT,
        /** Another mod calling through the public API. */
        MOD,
        /** A player-driven call from the client. */
        PLAYER
    }

    public static ToolCaller agent(boolean operator) {
        return new ToolCaller(Kind.AGENT, "tianshu", operator);
    }

    public static ToolCaller mod(String modId) {
        return new ToolCaller(Kind.MOD, modId, false);
    }

    public static ToolCaller player(String playerId, boolean operator) {
        return new ToolCaller(Kind.PLAYER, playerId, operator);
    }
}