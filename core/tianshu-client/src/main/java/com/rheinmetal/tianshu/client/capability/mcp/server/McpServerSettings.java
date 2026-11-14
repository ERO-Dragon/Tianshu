package com.rheinmetal.tianshu.client.capability.mcp.server;

/** The optional local MCP endpoint that exposes readable capabilities to external agents. */
public record McpServerSettings(boolean enabled, int port) {

    public static McpServerSettings disabled() {
        return new McpServerSettings(false, 8765);
    }
}
