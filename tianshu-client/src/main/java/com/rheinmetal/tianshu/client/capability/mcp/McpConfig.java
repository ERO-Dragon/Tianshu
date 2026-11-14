package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
                if (!spec.name().isBlank() && !spec.url().isBlank()) {
                    specs.add(spec);
                }
            }
            return List.copyOf(specs);
        } catch (IOException | RuntimeException malformed) {
            return List.of();
        }
    }
}
