package com.rheinmetal.tianshu.function.weaving.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;

/**
 * Reads one line of an OpenAI-compatible chat-completions server-sent-event stream.
 *
 * <p>Every frame is a single {@code data: {...}} line and the stream ends with {@code data: [DONE]}.
 * Providers additionally send comments ({@code : keep-alive}), unrelated fields ({@code event: ...})
 * and occasionally a truncated frame, so a line that carries no delta is reported as {@code null}
 * instead of failing the stream: one broken frame must not abort a generation that already produced
 * tokens for the text-to-speech pipeline.
 *
 * <p>Field names are tolerated rather than assumed: relays disagree on where reasoning text and the
 * prompt-cache split live. The three known reasoning spellings are tried in order and the first
 * non-empty one wins (some gateways send two of them with identical content), and the cache-read
 * count is accepted from the nested OpenAI shape, the DeepSeek spelling, or the flat Kimi shape. A
 * hit that arrives without a miss count is completed from {@code prompt_tokens} instead of being
 * reported as "not measured".
 */
final class OpenAiChatStreamParser {
    static final String DONE_MARKER = "[DONE]";
    private static final String DATA_PREFIX = "data:";
    private static final String COMMENT_PREFIX = ":";
    private static final String CONTENT_FIELD = "content";
    /** Reasoning spellings seen in the wild, in priority order. */
    private static final List<String> REASONING_FIELDS = List.of("reasoning_content", "reasoning", "reasoning_text");
    private static final String PROMPT_DETAILS_FIELD = "prompt_tokens_details";

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
            Integer cacheMissTokens,
            String finishReason
    ) {
        Event {
            text = text == null ? "" : text;
            thinking = thinking == null ? "" : thinking;
            finishReason = finishReason == null ? "" : finishReason;
        }

        static Event endOfStream() {
            return new Event("", "", true, null, null, null, null, "");
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
        JsonObject usage = usageObject(frame);
        Integer promptTokens = intField(usage, "prompt_tokens");
        Integer cacheHitTokens = firstNonNull(
                nestedIntField(usage, PROMPT_DETAILS_FIELD, "cached_tokens"),
                intField(usage, "prompt_cache_hit_tokens"),
                intField(usage, "cached_tokens")
        );
        Integer cacheMissTokens = intField(usage, "prompt_cache_miss_tokens");
        if (cacheMissTokens == null && cacheHitTokens != null && promptTokens != null) {
            // Follow the OpenAI meaning of cached_tokens: it is a read of the prompt, so the rest of
            // the prompt is the miss. Without this a gateway that only reports the hit would be read
            // as "cache split not reported" and the ledger would lose a figure it does have.
            cacheMissTokens = Math.max(0, promptTokens - cacheHitTokens);
        }
        return new Event(
                content(frame, CONTENT_FIELD),
                firstNonEmptyContent(frame, REASONING_FIELDS),
                false,
                promptTokens,
                intField(usage, "completion_tokens"),
                cacheHitTokens,
                cacheMissTokens,
                choiceField(frame, "finish_reason")
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

    /** The first reasoning field that carries text in this delta; empty when none does. */
    private static String firstNonEmptyContent(JsonObject frame, List<String> fields) {
        for (String field : fields) {
            String value = content(frame, field);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static JsonObject choice(JsonObject frame) {
        JsonElement choices = frame.get("choices");
        if (choices == null || !choices.isJsonArray()) {
            return null;
        }
        JsonArray array = choices.getAsJsonArray();
        if (array.isEmpty()) {
            return null;
        }
        JsonElement choice = array.get(0);
        return choice != null && choice.isJsonObject() ? choice.getAsJsonObject() : null;
    }

    private static JsonObject delta(JsonObject frame) {
        JsonObject choice = choice(frame);
        if (choice == null) {
            return null;
        }
        JsonElement delta = choice.get("delta");
        return delta != null && delta.isJsonObject() ? delta.getAsJsonObject() : null;
    }

    /**
     * The usage object of this frame: the standard top-level one, or the legacy placement inside the
     * first choice that some gateways still use.
     */
    private static JsonObject usageObject(JsonObject frame) {
        JsonElement usage = frame.get("usage");
        if (usage != null && usage.isJsonObject()) {
            return usage.getAsJsonObject();
        }
        JsonObject choice = choice(frame);
        if (choice == null) {
            return null;
        }
        JsonElement choiceUsage = choice.get("usage");
        return choiceUsage != null && choiceUsage.isJsonObject() ? choiceUsage.getAsJsonObject() : null;
    }

    private static Integer intField(JsonObject object, String field) {
        if (object == null || field == null) {
            return null;
        }
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return Math.max(0, value.getAsInt());
    }

    private static Integer nestedIntField(JsonObject object, String parent, String field) {
        if (object == null || parent == null) {
            return null;
        }
        JsonElement nested = object.get(parent);
        return nested != null && nested.isJsonObject()
                ? intField(nested.getAsJsonObject(), field)
                : null;
    }

    /** A string field of the first choice ({@code finish_reason}), or an empty string when absent. */
    private static String choiceField(JsonObject frame, String field) {
        JsonObject choice = choice(frame);
        if (choice == null) {
            return "";
        }
        JsonElement value = choice.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : "";
    }

    private static Integer firstNonNull(Integer... values) {
        for (Integer value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
