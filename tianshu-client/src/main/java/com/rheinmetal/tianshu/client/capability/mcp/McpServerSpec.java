package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;

import java.util.Objects;

/** One configured MCP endpoint. Only HTTP (streamable) is supported today; stdio can be added as another transport. */
public record McpServerSpec(String name, String url, ToolAutonomy autonomy) {

    public McpServerSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(autonomy, "autonomy");
    }

    static McpServerSpec fromJson(JsonObject json) {
        String name = json.has("name") ? json.get("name").getAsString() : "";
        String url = json.has("url") ? json.get("url").getAsString() : "";
        String level = json.has("autonomy") ? json.get("autonomy").getAsString() : "full";
        ToolAutonomy autonomy;
        try {
            autonomy = ToolAutonomy.valueOf(level.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException malformed) {
            autonomy = ToolAutonomy.FULL;
        }
        return new McpServerSpec(name, url, autonomy);
    }
}
