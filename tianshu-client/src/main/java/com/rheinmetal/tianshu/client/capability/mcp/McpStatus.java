package com.rheinmetal.tianshu.client.capability.mcp;

import java.util.List;

/** The platform-side view of MCP health: what is connected and a manual way to retry. */
public interface McpStatus {

    List<McpServerStatus> status();

    /** Forces a reconnect of one server (or all when {@code name} is unknown). Runs off the calling thread. */
    void reconnect(String name);
}
