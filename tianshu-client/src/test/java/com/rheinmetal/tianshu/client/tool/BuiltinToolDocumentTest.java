package com.rheinmetal.tianshu.client.tool;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolArgumentValidator;
import com.rheinmetal.tianshu.api.tool.ToolId;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinToolDocumentTest {
    private static final Gson GSON = new Gson();
    private static final Pattern EXAMPLE = Pattern.compile("(?ms)^```json\\s+(.*?)^```");

    @TestFactory
    List<DynamicTest> everyBuiltinHasCallableExamples() {
        List<TianshuTool<?, ?>> tools = new ArrayList<>();
        ClientTools.forEachBuiltin(tools::add, tools::add);
        return tools.stream().map(tool -> DynamicTest.dynamicTest(tool.id().toString(),
                () -> {
                    if (tool.id().equals(CapabilityReadTool.ID)) {
                        verifyReaderGuide(tool, "player-tools");
                        verifyReaderGuide(tool, "entity-tools");
                    } else {
                        verifyReference(tool);
                    }
                })).toList();
    }

    private void verifyReaderGuide(TianshuTool<?, ?> reader, String skill) throws Exception {
        String root = "/tianshu/skills/" + skill + "/";
        try (var input = getClass().getResourceAsStream(root + "SKILL.md")) {
            assertNotNull(input);
            var examples = EXAMPLE.matcher(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            while (examples.find()) {
                var call = JsonParser.parseString(examples.group(1)).getAsJsonObject();
                if (call.has("id") && reader.id().toString().equals(call.get("id").getAsString())) {
                    var args = call.getAsJsonObject("args");
                    assertTrue(ToolArgumentValidator.validate(reader.argsSchema(), args).isEmpty());
                    var target = ToolId.parse(args.get("id").getAsString());
                    assertNotNull(getClass().getResource(root + "references/" + target.name() + ".md"));
                    return;
                }
            }
            fail("Missing document reader example in " + skill);
        }
    }

    private void verifyReference(TianshuTool<?, ?> tool) throws Exception {
        var name = tool.id().name();
        var skill = name.startsWith("entity.") ? "entity-tools" : "player-tools";
        var path = "/tianshu/skills/" + skill + "/references/" + name + ".md";
        try (var input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, path);
            var examples = EXAMPLE.matcher(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            boolean hasCall = false;
            boolean hasResult = false;
            while (examples.find()) {
                var json = JsonParser.parseString(examples.group(1)).getAsJsonObject();
                if (json.has("args")) {
                    assertEquals(tool.id().toString(), json.get("id").getAsString());
                    JsonObject args = json.getAsJsonObject("args");
                    var schema = tool.argsSchema();
                    var errors = ToolArgumentValidator.validate(schema, args);
                    assertTrue(errors.isEmpty(), errors::toString);
                    if (schema != null && schema.has("properties")) {
                        assertTrue(schema.getAsJsonObject("properties").keySet().containsAll(args.keySet()), args::toString);
                    }
                    hasCall = true;
                } else {
                    assertTrue(json.get("ok").getAsBoolean());
                    var result = json.get("result");
                    assertNotNull(result);
                    Object typed = GSON.fromJson(result, tool.resultType());
                    assertEquals(result, GSON.toJsonTree(typed, tool.resultType()));
                    hasResult = true;
                }
            }
            assertTrue(hasCall && hasResult, "Expected a call and its result");
        }
    }
}
