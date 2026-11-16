package com.rheinmetal.tianshu.api.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a module's body-type declarations from the JSON file the adapter scans during the registration pass.
 *
 * <p>The file lives at {@code META-INF/tianshu/body_types.json} inside the module's jar, so no loader code is needed on
 * the module side and a server operator can edit the same declaration by hand:</p>
 *
 * <pre>{@code
 * [ { "name": "orb", "width": 0.5, "height": 0.5, "fireImmune": true } ]
 * }</pre>
 *
 * <p>Defaults follow the walking carrier (0.6 × 1.8, not fire immune) so the smallest useful declaration is just a
 * name. Every problem names the index and the field: a silently ignored declaration would mean the type never exists,
 * and the symptom would be "the body did not spawn" much later.</p>
 */
public final class EntityTypeDeclarations {

    /** Where a module puts its declarations, relative to the jar root. */
    public static final String RESOURCE_PATH = "META-INF/tianshu/body_types.json";

    private static final float DEFAULT_WIDTH = 0.6F;
    private static final float DEFAULT_HEIGHT = 1.8F;

    private EntityTypeDeclarations() {
    }

    /**
     * Parses one module's declarations.
     *
     * @throws IllegalArgumentException when the JSON is not an array of well-formed objects, or repeats a name
     */
    public static List<EntityTypeSpec> parse(String modId, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("body_types.json is not valid JSON", malformed);
        }
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("body_types.json must be a JSON array of declarations");
        }
        JsonArray entries = root.getAsJsonArray();
        List<EntityTypeSpec> specs = new ArrayList<>(entries.size());
        Set<String> names = new HashSet<>();
        for (int index = 0; index < entries.size(); index++) {
            JsonElement element = entries.get(index);
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("body_types.json[" + index + "] must be an object");
            }
            JsonObject entry = element.getAsJsonObject();
            String name = text(entry, index, "name", null);
            if (name == null) {
                throw new IllegalArgumentException("body_types.json[" + index + "].name is required");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("body_types.json declares \"" + name + "\" twice");
            }
            float width = number(entry, index, "width", DEFAULT_WIDTH);
            float height = number(entry, index, "height", DEFAULT_HEIGHT);
            boolean fireImmune = bool(entry, index, "fireImmune", false);
            specs.add(new EntityTypeSpec(modId, name, width, height, fireImmune));
        }
        return List.copyOf(specs);
    }

    private static String text(JsonObject entry, int index, String field, String fallback) {
        JsonElement value = entry.get(field);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("body_types.json[" + index + "]." + field + " must be a string");
        }
        return value.getAsString();
    }

    private static float number(JsonObject entry, int index, String field, float fallback) {
        JsonElement value = entry.get(field);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("body_types.json[" + index + "]." + field + " must be a number");
        }
        return value.getAsFloat();
    }

    private static boolean bool(JsonObject entry, int index, String field, boolean fallback) {
        JsonElement value = entry.get(field);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("body_types.json[" + index + "]." + field + " must be a boolean");
        }
        return value.getAsBoolean();
    }
}