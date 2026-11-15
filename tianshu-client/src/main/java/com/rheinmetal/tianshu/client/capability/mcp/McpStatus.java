package com.rheinmetal.tianshu.client.capability.mcp;

import java.util.List;

/** The platform-side view of MCP health: what is connected, what went wrong, and a manual way to retry. */
public interface McpStatus {

    /** Latest state per configured server; reading it must not wait for a handshake in progress. */
    List<McpServerStatus> status();

    /**
     * Everything that went wrong recently, newest last: unreadable config, a server that will not start, a remote tool
     * whose id collides.
     *
     * <p>Part of the port because these used to be collected and read by nobody: a misconfigured server and an
     * unconfigured one looked identical from the outside.</p>
     */
    List<String> problems();

    /** Forces a reconnect attempt of the configured servers; {@code name} identifies the trigger. */
    void reconnect(String name);
}