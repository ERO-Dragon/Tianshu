package com.rheinmetal.tianshu.client.capability.mcp;

import java.util.List;

/** The platform-side view of MCP health: what is connected and a manual way to retry. */
public interface McpStatus {

    List<McpServerStatus> status();

    /** Forces a reconnect attempt of the configured servers; {@code name} identifies the trigger. */
    void reconnect(String name);
}
