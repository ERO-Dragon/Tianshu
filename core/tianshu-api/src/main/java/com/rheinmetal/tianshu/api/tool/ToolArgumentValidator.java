package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately small JSON-Schema check for tool arguments: {@code type}, {@code required}, {@code properties},
 * {@code additionalProperties: false}, {@code enum}, numeric bounds, string length and array items.
 *
 * <p>It is not a full validator and does not pretend to be: unsupported keywords are ignored rather than rejected,
 * so a richer schema never blocks a call. The point is to catch the ordinary "wrong type / missing field" mistakes
 * before the tool runs, with a path the model can repair.</p>
 */
public final class ToolArgumentValidator {

    private static final int MAX_DEPTH = 8;

    private ToolArgumentValidator() {
    }

    public static List<String> validate(JsonObject schema, JsonElement arguments) {
        List<String> errors = new ArrayList<>();
        if (schema == null || schema.size() == 0) {
            return errors;
        }
        JsonElement value = arguments == null ? new JsonObject() : arguments;
        check(schema, value, "args", 0, errors);
        return errors;
    }

    private static void check(JsonObject schema, JsonElement value, String path, int depth, List<String> errors) {
        if (depth > MAX_DEPTH) {
            return;
        }
        String type = string(schema, "type");
        if (type != null && !matchesType(type, value)) {
            errors.add(path + " must be " + type);
            return;
        }
        if (schema.has("enum") && schema.get("enum").isJsonArray()) {
            boolean matched = false;
            for (JsonElement allowed : schema.getAsJsonArray("enum")) {
                if (allowed.equals(value)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                errors.add(path + " must be one of " + schema.getAsJsonArray("enum"));
            }
        }
        if (value.isJsonObject()) {
            checkObject(schema, value.getAsJsonObject(), path, depth, errors);
        } else if (value.isJsonArray()) {
            checkArray(schema, value.getAsJsonArray(), path, depth, errors);
        }
        // Numeric and length bounds are intentionally not enforced here: several tools clamp a request into their
        // advertised range instead of rejecting it, and the schema is the model's guide, not a second clamp.
    }

    private static void checkObject(JsonObject schema, JsonObject value, String path, int depth,
                                    List<String> errors) {
        if (schema.has("required") && schema.get("required").isJsonArray()) {
            for (JsonElement required : schema.getAsJsonArray("required")) {
                if (!required.isJsonPrimitive() || !value.has(required.getAsString())) {
                    errors.add(path + "." + (required.isJsonPrimitive() ? required.getAsString() : "?")
                            + " is required");
                }
            }
        }
        if (schema.has("properties") && schema.get("properties").isJsonObject()) {
            JsonObject properties = schema.getAsJsonObject("properties");
            for (String key : properties.keySet()) {
                if (value.has(key) && properties.get(key).isJsonObject()) {
                    check(properties.getAsJsonObject(key), value.get(key), path + "." + key, depth + 1, errors);
                }
            }
            if (schema.has("additionalProperties") && schema.get("additionalProperties").isJsonPrimitive()
                    && schema.get("additionalProperties").getAsJsonPrimitive().isBoolean()
                    && !schema.get("additionalProperties").getAsBoolean()) {
                for (String key : value.keySet()) {
                    if (!properties.has(key)) {
                        errors.add(path + "." + key + " is not allowed");
                    }
                }
            }
        }
    }

    private static void checkArray(JsonObject schema, JsonElement array, String path, int depth,
                                   List<String> errors) {
        if (schema.has("minItems") && array.getAsJsonArray().size() < schema.get("minItems").getAsInt()) {
            errors.add(path + " must have at least " + schema.get("minItems").getAsInt() + " items");
        }
        if (schema.has("maxItems") && array.getAsJsonArray().size() > schema.get("maxItems").getAsInt()) {
            errors.add(path + " must have at most " + schema.get("maxItems").getAsInt() + " items");
        }
        if (schema.has("items") && schema.get("items").isJsonObject()) {
            JsonObject itemSchema = schema.getAsJsonObject("items");
            int index = 0;
            for (JsonElement item : array.getAsJsonArray()) {
                check(itemSchema, item, path + "[" + index + "]", depth + 1, errors);
                index++;
            }
        }
    }

    private static String string(JsonObject schema, String key) {
        return schema.has(key) && schema.get(key).isJsonPrimitive() ? schema.get(key).getAsString() : null;
    }

    private static boolean matchesType(String type, JsonElement value) {
        return switch (type) {
            case "object" -> value.isJsonObject();
            case "array" -> value.isJsonArray();
            case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && value.getAsDouble() == Math.rint(value.getAsDouble());
            case "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "null" -> value.isJsonNull();
            default -> true;
        };
    }
}
