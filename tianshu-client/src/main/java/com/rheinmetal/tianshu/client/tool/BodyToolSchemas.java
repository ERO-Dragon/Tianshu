package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.agent.BlockTarget;

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

    /**
     * A block or position coordinate.
     *
     * <p>The bounds are the ones {@link BlockTarget} enforces, written out so a model can see them instead of learning
     * them from a rejection. Two places that both "know" the range is exactly the drift this class exists to avoid, so
     * the number comes from the value type.</p>
     */
    static JsonObject coordinate(String description) {
        JsonObject property = integer(description + "; at most " + BlockTarget.MAX_COORDINATE + " blocks from origin");
        property.addProperty("minimum", -BlockTarget.MAX_COORDINATE);
        property.addProperty("maximum", BlockTarget.MAX_COORDINATE);
        return property;
    }

    static JsonObject number(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "number");
        property.addProperty("description", description);
        return property;
    }

    /** A numeric position coordinate, bounded like {@link #coordinate(String)}. */
    static JsonObject position(String description) {
        JsonObject property = number(description + "; at most " + BlockTarget.MAX_COORDINATE + " blocks from origin");
        property.addProperty("minimum", -BlockTarget.MAX_COORDINATE);
        property.addProperty("maximum", BlockTarget.MAX_COORDINATE);
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