package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Optional;

/**
 * Reads the tool protocol out of one model reply.
 *
 * <p>The protocol is one JSON object and nothing else: {@code {"tool": "namespace:name", "args": {...}}}. Fenced
 * code blocks are tolerated because models wrap JSON in them even when told not to; a reply that is not a call is
 * an answer.</p>
 *
 * <p>{@link #parseDetailed} keeps the reason, so the agent can tell the model "this looked like a call but was
 * malformed" and let it repair the call instead of treating the broken JSON as an answer.</p>
 */
public final class ToolCallParser {

    /** A parsed call, or the reason the reply looked like a call but was not usable. */
    public record ParseResult(Optional<ToolCall> call, String error) {

        static ParseResult of(ToolCall call) {
            return new ParseResult(Optional.of(call), "");
        }

        static ParseResult failure(String error) {
            return new ParseResult(Optional.empty(), error);
        }
    }

    private ToolCallParser() {
    }

    public static Optional<ToolCall> parse(String reply) {
        return parseDetailed(reply).call();
    }

    public static ParseResult parseDetailed(String reply) {
        if (reply == null) {
            return ParseResult.failure("the reply is empty");
        }
        String text = reply.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int closingFence = text.lastIndexOf("```");
            if (firstNewline < 0 || closingFence <= firstNewline) {
                return ParseResult.failure("the fenced block is not closed");
            }
            text = text.substring(firstNewline + 1, closingFence).trim();
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text);
        } catch (RuntimeException notJson) {
            return ParseResult.failure("the JSON could not be parsed: " + notJson.getMessage());
        }
        if (!parsed.isJsonObject()) {
            return ParseResult.failure("the reply is not a JSON object");
        }
        JsonObject object = parsed.getAsJsonObject();
        if (!object.has("tool") || !object.get("tool").isJsonPrimitive()) {
            return ParseResult.failure("the object has no string \"tool\" field");
        }
        ToolId id;
        try {
            id = ToolId.parse(object.get("tool").getAsString());
        } catch (IllegalArgumentException malformed) {
            return ParseResult.failure("\"tool\" is not namespace:name");
        }
        JsonObject args = new JsonObject();
        if (object.has("args")) {
            JsonElement value = object.get("args");
            if (!value.isJsonObject()) {
                return ParseResult.failure("\"args\" must be a JSON object");
            }
            args = value.getAsJsonObject();
        }
        return ParseResult.of(new ToolCall(id, args));
    }
}
