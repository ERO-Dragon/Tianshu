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

    /**
     * The body parameter is optional on the wire: the caller runtime already knows which body it is driving and
     * fills this in before dispatch, so the model does not spend tokens repeating an id it did not choose.
     */
    static JsonObject body() {
        return text("Agent body id; the caller runtime fills it in when omitted");
    }

    static JsonObject integer(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("description", description);
        return property;
    }

    static JsonObject number(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "number");
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