package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.client.capability.mcp.server.McpServerSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code config/Tianshu/mcp.json}: {@code {"servers":[{"name":"...","url":"...","autonomy":"conservative"}]}}.
 *
 * <p>Every way this file can be wrong is reported: absent is normal and stays quiet (the feature is opt-in), but an
 * unreadable or malformed file used to produce the same empty result as "not configured", so a typo in a command line
 * or a trailing comma looked exactly like "MCP has no servers". The caller supplies the sink because this class has no
 * runtime of its own.</p>
 */
public final class McpConfig {

    private McpConfig() {
    }

    /** The optional local server section; disabled unless the file says otherwise. */
    public static McpServerSettings server(Path file, LogSink logs) {
        JsonObject root = root(file, logs);
        if (root == null || !root.has("server") || !root.get("server").isJsonObject()) {
            return McpServerSettings.disabled();
        }
        JsonObject server = root.getAsJsonObject("server");
        boolean enabled = server.has("enabled") && server.get("enabled").isJsonPrimitive()
                && server.get("enabled").getAsBoolean();
        int port = server.has("port") && server.get("port").isJsonPrimitive() ? server.get("port").getAsInt() : 8765;
        return new McpServerSettings(enabled, port);
    }

    public static List<McpServerSpec> load(Path file, LogSink logs) {
        LogSink sink = logs == null ? LogSink.NOOP : logs;
        JsonObject root = root(file, sink);
        if (root == null) {
            return List.of();
        }
        JsonElement servers = root.get("servers");
        if (servers == null || !servers.isJsonArray()) {
            sink.warn("mcp: " + file + " has no \"servers\" array, so no server is configured");
            return List.of();
        }
        JsonArray entries = servers.getAsJsonArray();
        List<McpServerSpec> specs = new ArrayList<>(entries.size());
        int skipped = 0;
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                skipped++;
                continue;
            }
            McpServerSpec spec = McpServerSpec.fromJson(element.getAsJsonObject());
            if (spec.name().isBlank() || (spec.url().isBlank() && spec.command().isEmpty())) {
                skipped++;
                continue;
            }
            specs.add(spec);
        }
        if (skipped > 0) {
            sink.warn("mcp: " + file + " has " + skipped + " server entr" + (skipped == 1 ? "y" : "ies")
                    + " without a usable name and url/command; the remaining " + specs.size() + " were loaded");
        }
        return List.copyOf(specs);
    }

    /**
     * The parsed document, or {@code null} when this file cannot supply one.
     *
     * <p>Absence is a normal state and is reported at INFO (which a release build does not write); anything else — an
     * unreadable file, invalid JSON — is a warning, because the user believes they configured something.</p>
     */
    private static JsonObject root(Path file, LogSink logs) {
        LogSink sink = logs == null ? LogSink.NOOP : logs;
        if (file == null) {
            return null;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException absent) {
            sink.info("mcp: no configuration at " + file);
            return null;
        } catch (IOException unreadable) {
            sink.warn("mcp: cannot read " + file, unreadable);
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                sink.warn("mcp: " + file + " must contain a JSON object");
                return null;
            }
            return parsed.getAsJsonObject();
        } catch (JsonParseException malformed) {
            sink.warn("mcp: " + file + " is not valid JSON", malformed);
            return null;
        }
    }
}