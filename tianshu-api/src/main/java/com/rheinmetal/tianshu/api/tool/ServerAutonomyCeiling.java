package com.rheinmetal.tianshu.api.tool;

/**
 * The server's own ceiling on how much an agent may do here.
 *
 * <p>It is a host port rather than a global value, for two reasons: only the adapter knows what this process is
 * (a dedicated server an operator configured, or a single-player world), and the operator is the party that should
 * decide. The server applies it to server-side tool execution regardless of what the caller claims about itself —
 * the client's own tier narrows what the client offers, it never widens what the server allows.</p>
 *
 * <p>Absence from {@link com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts} means "no extra restriction": that
 * is the single-player and development case, where the caller is already the owner of the machine.</p>
 */
public interface ServerAutonomyCeiling {

    /** The highest tier a server-side tool may require on this server. */
    ToolAutonomy max();
}