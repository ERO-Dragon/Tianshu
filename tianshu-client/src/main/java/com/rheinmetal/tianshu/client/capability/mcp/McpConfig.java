package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.client.capability.mcp.server.McpServerSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads {@code config/Tianshu/mcp.json}: {@code {"servers":[{"name":"...","url":"...","autonomy":"full"}]}}. */
public final class McpConfig {

    private McpConfig() {
    }

    /** The optional local server section; disabled unless the file says otherwise. */
    public static McpServerSettings server(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return McpServerSettings.disabled();
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("server")) {
                return McpServerSettings.disabled();
            }
            JsonObject server = root.getAsJsonObject().getAsJsonObject("server");
            boolean enabled = server.has("enabled") && server.get("enabled").getAsBoolean();
            int port = server.has("port") ? server.get("port").getAsInt() : 8765;
            return new McpServerSettings(enabled, port);
        } catch (IOException | RuntimeException malformed) {
            return McpServerSettings.disabled();
        }
    }

    public static List<McpServerSpec> load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("servers")) {
                return List.of();
            }
            JsonArray servers = root.getAsJsonObject().getAsJsonArray("servers");
            List<McpServerSpec> specs = new ArrayList<>();
            for (JsonElement element : servers) {
                if (!element.isJsonObject()) {
                    continue;
                }
                McpServerSpec spec = McpServerSpec.fromJson(element.getAsJsonObject());
                if (!spec.name().isBlank() && (!spec.url().isBlank() || !spec.command().isEmpty())) {
                    specs.add(spec);
                }
            }
            return List.copyOf(specs);
        } catch (IOException | RuntimeException malformed) {
            return List.of();
        }
    }
}
