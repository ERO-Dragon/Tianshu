package com.rheinmetal.tianshu.function.weaving.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads one line of an OpenAI-compatible chat-completions server-sent-event stream.
 *
 * <p>Every frame is a single {@code data: {...}} line and the stream ends with {@code data: [DONE]}.
 * Providers additionally send comments ({@code : keep-alive}), unrelated fields ({@code event: ...})
 * and occasionally a truncated frame, so a line that carries no delta is reported as {@code null}
 * instead of failing the stream: one broken frame must not abort a generation that already produced
 * tokens for the text-to-speech pipeline.
 */
final class OpenAiChatStreamParser {
    static final String DONE_MARKER = "[DONE]";
    private static final String DATA_PREFIX = "data:";
    private static final String COMMENT_PREFIX = ":";
    private static final String CONTENT_FIELD = "content";
    private static final String REASONING_FIELD = "reasoning_content";

    private OpenAiChatStreamParser() {
    }

    /**
     * One parsed frame. The text fields are empty when the frame carries no content, and the token
     * counts stay {@code null} unless the frame carries a usage report.
     *
     * <p>{@code cacheHitTokens} / {@code cacheMissTokens} are the provider's own account of how much
     * of the prompt it served from its prefix cache. They are the only ground truth we have about
     * whether our prompt is actually cache-stable, so they are carried through verbatim.
     */
    record Event(
            String text,
            String thinking,
            boolean done,
            Integer promptTokens,
            Integer completionTokens,
            Integer cacheHitTokens,
            Integer cacheMissTokens
    ) {
        Event {
            text = text == null ? "" : text;
            thinking = thinking == null ? "" : thinking;
        }

        static Event endOfStream() {
            return new Event("", "", true, null, null, null, null);
        }
    }

    /**
     * Returns the frame carried by {@code rawLine}, or {@code null} when the line is blank, is not a
     * data field, or cannot be decoded.
     */
    static Event parse(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith(COMMENT_PREFIX) || !line.startsWith(DATA_PREFIX)) {
            return null;
        }
        String payload = line.substring(DATA_PREFIX.length()).trim();
        if (payload.isEmpty()) {
            return null;
        }
        if (DONE_MARKER.equals(payload)) {
            return Event.endOfStream();
        }
        JsonObject frame = parseObject(payload);
        if (frame == null) {
            return null;
        }
        return new Event(
                content(frame, CONTENT_FIELD),
                content(frame, REASONING_FIELD),
                false,
                usage(frame, "prompt_tokens"),
                usage(frame, "completion_tokens"),
                usage(frame, "prompt_cache_hit_tokens"),
                usage(frame, "prompt_cache_miss_tokens")
        );
    }

    private static JsonObject parseObject(String payload) {
        try {
            JsonElement decoded = JsonParser.parseString(payload);
            return decoded != null && decoded.isJsonObject() ? decoded.getAsJsonObject() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** Incremental text of {@code choices[0].delta.<field>}, or an empty string when absent. */
    private static String content(JsonObject frame, String field) {
        JsonObject delta = delta(frame);
        if (delta == null) {
            return "";
        }
        JsonElement value = delta.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : "";
    }

    private static JsonObject delta(JsonObject frame) {
        JsonElement choices = frame.get("choices");
        if (choices == null || !choices.isJsonArray()) {
            return null;
        }
        JsonArray array = choices.getAsJsonArray();
        if (array.isEmpty()) {
            return null;
        }
        JsonElement choice = array.get(0);
        if (choice == null || !choice.isJsonObject()) {
            return null;
        }
        JsonElement delta = choice.getAsJsonObject().get("delta");
        return delta != null && delta.isJsonObject() ? delta.getAsJsonObject() : null;
    }

    private static Integer usage(JsonObject frame, String field) {
        JsonElement usage = frame.get("usage");
        if (usage == null || !usage.isJsonObject()) {
            return null;
        }
        JsonElement value = usage.getAsJsonObject().get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return Math.max(0, value.getAsInt());
    }
}
