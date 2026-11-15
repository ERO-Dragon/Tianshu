package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Json-schema fragments shared by the body tools, so five schemas do not drift apart. */
final class BodyToolSchemas {

    private BodyToolSchemas() {
    }

    static JsonObject text(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", description);
        return property;
    }

    static JsonObject integer(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("description", description);
        return property;
    }

    static JsonObject object(JsonObject properties, String... required) {
        JsonArray names = new JsonArray();
        for (String name : required) {
            names.add(name);
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", names);
        return schema;
    }
}