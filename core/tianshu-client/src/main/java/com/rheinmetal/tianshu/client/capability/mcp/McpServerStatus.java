package com.rheinmetal.tianshu.client.capability.mcp;

/** One MCP server's health, as exposed to the rest of the platform. */
public record McpServerStatus(String name, McpServerSpec.Type type, boolean connected, int toolCount,
                              String lastError) {
}
