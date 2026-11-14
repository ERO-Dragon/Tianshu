package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The small schema check: required, type, additionalProperties, enum, bounds, array items. */
class ToolArgumentValidatorTest {

    private static JsonObject schema(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonElement args(String json) {
        return JsonParser.parseString(json);
    }

    @Test
    void missingRequiredFieldAndWrongTypeAreReportedWithAPath() {
        JsonObject schema = schema("""
                {"type":"object","required":["text"],"properties":{"text":{"type":"string"}}}
                """);

        assertTrue(ToolArgumentValidator.validate(schema, args("{}")).get(0).contains("args.text"));
        assertTrue(ToolArgumentValidator.validate(schema, args("{\"text\":3}")).get(0)
                .contains("args.text must be string"));
    }

    @Test
    void additionalPropertiesEnumBoundsAndArrayItemsAreChecked() {
        JsonObject schema = schema("""
                {
                  "type":"object",
                  "additionalProperties":false,
                  "properties":{
                    "count":{"type":"integer","minimum":1,"maximum":64},
                    "mode":{"type":"string","enum":["a","b"]},
                    "ids":{"type":"array","maxItems":2,"items":{"type":"string"}}
                  }
                }
                """);

        assertTrue(ToolArgumentValidator.validate(schema, args("{\"extra\":1}")).get(0).contains("not allowed"));
        // Bounds are advisory: tools that clamp should not be rejected before they run.
        assertTrue(ToolArgumentValidator.validate(schema, args("{\"count\":0}")).isEmpty());
        assertTrue(ToolArgumentValidator.validate(schema, args("{\"mode\":\"c\"}")).get(0).contains("one of"));
        assertTrue(ToolArgumentValidator.validate(schema, args("{\"ids\":[1]}")).get(0)
                .contains("args.ids[0] must be string"));
        assertTrue(ToolArgumentValidator.validate(schema,
                args("{\"count\":2,\"mode\":\"a\",\"ids\":[\"x\"]}")).isEmpty());
    }

    @Test
    void anEmptySchemaAcceptsEverything() {
        assertTrue(ToolArgumentValidator.validate(new JsonObject(), args("{\"anything\":1}")).isEmpty());
        assertTrue(ToolArgumentValidator.validate(null, args("{}")).isEmpty());
    }
}
