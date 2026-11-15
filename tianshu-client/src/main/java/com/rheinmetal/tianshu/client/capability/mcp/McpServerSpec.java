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
        String name = text(json, "name", "");
        String url = text(json, "url", "");
        String type = text(json, "type", url.isBlank() ? "stdio" : "http");
        ToolAutonomy autonomy = ToolAutonomy.parse(text(json, "autonomy", DEFAULT_AUTONOMY.name()), DEFAULT_AUTONOMY);
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

    /**
     * The default tier for a server that does not state one.
     *
     * <p>{@code CONSERVATIVE} on purpose: a server that forgot to say how much power it wants must not be handed
     * the privileged tier by omission. Raised by writing {@code "autonomy": "full"} in the config, which is a
     * deliberate act the operator takes.</p>
     */
    static final ToolAutonomy DEFAULT_AUTONOMY = ToolAutonomy.CONSERVATIVE;

    /** Reads one optional string; a non-primitive value is treated as absent instead of failing the whole file. */
    private static String text(JsonObject json, String key, String fallback) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()) {
            return fallback;
        }
        return json.get(key).getAsString();
    }
}
