package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One configured MCP endpoint: {@code http} (streamable) or {@code stdio} (a child process). */
public record McpServerSpec(String name, Type type, String url, List<String> command, ToolAutonomy autonomy) {

    public enum Type {
        HTTP,
        STDIO
    }

    public McpServerSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(autonomy, "autonomy");
        command = command == null ? List.of() : List.copyOf(command);
    }

    public static McpServerSpec http(String name, String url, ToolAutonomy autonomy) {
        return new McpServerSpec(name, Type.HTTP, url, List.of(), autonomy);
    }

    public static McpServerSpec stdio(String name, List<String> command, ToolAutonomy autonomy) {
        return new McpServerSpec(name, Type.STDIO, "", List.copyOf(command), autonomy);
    }

    static McpServerSpec fromJson(JsonObject json) {
        String name = json.has("name") ? json.get("name").getAsString() : "";
        String url = json.has("url") ? json.get("url").getAsString() : "";
        String type = json.has("type") ? json.get("type").getAsString() : (url.isBlank() ? "stdio" : "http");
        String level = json.has("autonomy") ? json.get("autonomy").getAsString() : "full";
        ToolAutonomy autonomy;
        try {
            autonomy = ToolAutonomy.valueOf(level.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException malformed) {
            autonomy = ToolAutonomy.FULL;
        }
        List<String> command = new ArrayList<>();
        if (json.has("command") && json.get("command").isJsonArray()) {
            JsonArray array = json.getAsJsonArray("command");
            for (JsonElement element : array) {
                if (element.isJsonPrimitive()) {
                    command.add(element.getAsString());
                }
            }
        }
        Type parsed = "stdio".equalsIgnoreCase(type) ? Type.STDIO : Type.HTTP;
        if (parsed == Type.STDIO && command.isEmpty()) {
            parsed = Type.HTTP;
        }
        return new McpServerSpec(name, parsed, url, command, autonomy);
    }
}
