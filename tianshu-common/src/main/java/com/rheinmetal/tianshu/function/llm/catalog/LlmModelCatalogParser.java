package com.rheinmetal.tianshu.function.llm.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads an OpenAI-compatible model-list body.
 *
 * <p>The documented shape is {@code {"object":"list","data":[{"id":"deepseek-chat",...}, ...]}}; see
 * https://platform.openai.com/docs/api-reference/models/list and the mirror DeepSeek publishes at
 * https://api-docs.deepseek.com/api/list-models (both read 2026-11). Only the {@code id} of each entry
 * is used: display name, window and image capability are configuration, not something a provider's
 * list answer states.
 *
 * <p>A bare array of {@code {"id": ...}} entries is accepted as well. Providers do drift from the
 * document, and a body that clearly is a model list should not be reported as unreadable just because
 * it is not wrapped in {@code data}.
 */
final class LlmModelCatalogParser {
    private static final String DATA_FIELD = "data";
    private static final String ID_FIELD = "id";

    private LlmModelCatalogParser() {
    }

    /**
     * Model ids of one answer body.
     *
     * @return the ids in provider order, possibly empty when the provider lists no model,
     *         {@link Optional#empty()} when the body is not a model list at all
     */
    static Optional<List<String>> parseModelIds(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (JsonParseException unreadable) {
            return Optional.empty();
        }
        if (root == null || root.isJsonNull()) {
            return Optional.empty();
        }
        if (root.isJsonArray()) {
            return collect(root.getAsJsonArray());
        }
        if (!root.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject object = root.getAsJsonObject();
        JsonElement data = object.get(DATA_FIELD);
        if (data == null || !data.isJsonArray()) {
            return Optional.empty();
        }
        return collect(data.getAsJsonArray());
    }

    /** {@link Optional#empty()} when an element is not an object with a usable id. */
    private static Optional<List<String>> collect(JsonArray entries) {
        List<String> ids = new ArrayList<>(entries.size());
        for (JsonElement entry : entries) {
            if (entry == null || !entry.isJsonObject()) {
                return Optional.empty();
            }
            JsonElement id = entry.getAsJsonObject().get(ID_FIELD);
            if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
                return Optional.empty();
            }
            String modelId = id.getAsString();
            if (modelId == null || modelId.isBlank()) {
                return Optional.empty();
            }
            ids.add(modelId.trim());
        }
        return Optional.of(List.copyOf(ids));
    }
}
