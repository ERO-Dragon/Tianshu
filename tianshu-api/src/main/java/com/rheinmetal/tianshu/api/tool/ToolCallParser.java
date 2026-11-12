package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.util.Optional;

/**
 * Reads the tool protocol out of one model reply.
 *
 * <p>The protocol is one JSON object and nothing else: {@code {"tool": "namespace:name", "args": {...}}}. Fenced
 * code blocks are tolerated because models wrap JSON in them even when told not to; anything else is an answer, not
 * a call.</p>
 */
public final class ToolCallParser {

    private ToolCallParser() {
    }

    public static Optional<ToolCall> parse(String reply) {
        if (reply == null) {
            return Optional.empty();
        }
        String text = reply.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int closingFence = text.lastIndexOf("```");
            if (firstNewline < 0 || closingFence <= firstNewline) {
                return Optional.empty();
            }
            text = text.substring(firstNewline + 1, closingFence).trim();
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text);
        } catch (RuntimeException notJson) {
            return Optional.empty();
        }
        if (!parsed.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject object = parsed.getAsJsonObject();
        if (!object.has("tool") || !object.get("tool").isJsonPrimitive()) {
            return Optional.empty();
        }
        ToolId id;
        try {
            id = ToolId.parse(object.get("tool").getAsString());
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        JsonObject args = new JsonObject();
        if (object.has("args")) {
            JsonElement value = object.get("args");
            if (!value.isJsonObject()) {
                return Optional.empty();
            }
            args = value.getAsJsonObject();
        }
        return Optional.of(new ToolCall(id, args));
    }
}
