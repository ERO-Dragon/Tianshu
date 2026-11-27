package com.rheinmetal.tianshu.api.tool;

import java.util.Objects;

/**
 * Who is asking. The same tool can be available to the conversation agent and to another mod's plain logic,
 * and a policy may treat those two differently.
 *
 * <p>{@code operator} is a property of the caller, not of the tool: a player-driven call inherits the player's
 * permission, while the agent only carries it when it is acting for such a player.</p>
 *
 * <p>{@code autonomy} is the caller's <em>own</em> ceiling: how far this caller has decided it may go on its own. It
 * belongs to the caller rather than to a process-wide setting because each AI module decides for itself — two agent
 * modules in one process are allowed different answers, and a global value could only ever express one of them. A
 * tool states what it needs with {@link TianshuTool#requiredAutonomy()}; a call is allowed when the caller's tier is
 * at least that.</p>
 *
 * <p>This tier is <b>not</b> an authorisation for server-side work. The server decides with
 * {@link ServerAutonomyCeiling}, {@link ToolPermission} and each tool's own switch; a caller can only narrow what it
 * is offered, never widen what the server allows.</p>
 */
public record ToolCaller(Kind kind, String id, boolean operator, ToolAutonomy autonomy, String actingFor) {

    public enum Kind {
        /** An AI module driving a conversation. */
        AGENT,
        /** Another mod calling through the public API. */
        MOD,
        /** A player-driven call from the client. */
        PLAYER,
        /** A server-side agent body acting in the world. */
        BODY
    }

    /** What a caller gets when it does not state a tier: the middle, survival-safe one. */
    public static final ToolAutonomy DEFAULT_AUTONOMY = ToolAutonomy.CONSERVATIVE;

    public ToolCaller {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        if (kind == Kind.AGENT || kind == Kind.MOD) {
            id = id.strip();
            if (id.isEmpty()) {
                throw new IllegalArgumentException("a module caller needs a module id");
            }
        }
        autonomy = autonomy == null ? DEFAULT_AUTONOMY : autonomy;
        actingFor = actingFor == null || actingFor.isBlank() ? null : actingFor.strip();
        if (actingFor != null && (kind == Kind.PLAYER || kind == Kind.BODY)) {
            // A player or a body already is who it is; a proxy on either would be a second, unverifiable identity.
            throw new IllegalArgumentException("only an agent or a module call can act for a player");
        }
    }

    /** Whether this call is being made on a player's behalf rather than by the player itself. */
    public boolean actsForAPlayer() {
        return actingFor != null;
    }

    /** The conversation agent, at the default tier. */
    public static ToolCaller agent(boolean operator) {
        return new ToolCaller(Kind.AGENT, "tianshu", operator, DEFAULT_AUTONOMY, null);
    }

    /** An agent module that manages its own tier; {@code moduleId} is the id it publishes as. */
    public static ToolCaller agent(String moduleId, boolean operator, ToolAutonomy autonomy) {
        return new ToolCaller(Kind.AGENT, moduleId, operator, autonomy, null);
    }

    /**
     * An agent acting for a player: the request came from that player's session, so the server may judge it by what that
     * player owns without trusting any claim about who the agent is.
     */
    public static ToolCaller agentFor(String moduleId, String playerId, boolean operator, ToolAutonomy autonomy) {
        return new ToolCaller(Kind.AGENT, moduleId, operator, autonomy, playerId);
    }

    /** The same agent, now acting for {@code playerId}. */
    public ToolCaller actingFor(String playerId) {
        return new ToolCaller(kind, id, operator, autonomy, playerId);
    }

    /**
     * Another mod calling through the public API.
     *
     * <p>{@link ToolAutonomy#FULL}: a mod is code its author wrote deliberately, not an agent acting on its own, so
     * there is no autonomy for it to restrain. Its reach is still bounded by the server's ceiling, the tool's
     * permission and each tool's switch — this only removes the self-restriction axis.</p>
     */
    public static ToolCaller mod(String modId) {
        return new ToolCaller(Kind.MOD, modId, false, ToolAutonomy.FULL, null);
    }

    /**
     * A player-driven call.
     *
     * <p>Deliberately still at the default tier: the same factory is used by modules that act <em>for</em> a player,
     * and a module's own tier must travel through its own {@link #agent(String, boolean, ToolAutonomy)} — otherwise
     * a module set to read-only would be bypassed on every turn that has a player.</p>
     */
    public static ToolCaller player(String playerId, boolean operator) {
        return new ToolCaller(Kind.PLAYER, playerId, operator, DEFAULT_AUTONOMY, null);
    }

    public static ToolCaller body(String bodyId, boolean operator) {
        return new ToolCaller(Kind.BODY, bodyId, operator, DEFAULT_AUTONOMY, null);
    }

    /** A body acting for a module that decides how far it may go. */
    public static ToolCaller body(String bodyId, boolean operator, ToolAutonomy autonomy) {
        return new ToolCaller(Kind.BODY, bodyId, operator, autonomy, null);
    }
}