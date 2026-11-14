package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tool-call protocol value and its two pure helpers: reply parsing and catalogue rendering. */
class ToolCallProtocolTest {

    @Test
    void parsesAPlainCall() {
        ToolCall call = ToolCallParser.parse("{\"tool\":\"test:echo\",\"args\":{\"text\":\"hi\"}}").orElseThrow();
        assertEquals(ToolId.of("test", "echo"), call.toolId());
        assertEquals("hi", call.args().get("text").getAsString());
    }

    @Test
    void toleratesFencedJsonAndMissingArgs() {
        assertTrue(ToolCallParser.parse("```json\n{\"tool\":\"test:echo\"}\n```").isPresent());
        assertEquals(0, ToolCallParser.parse("```\n{\"tool\":\"test:echo\"}\n```").orElseThrow().args().size());
    }

    @Test
    void anOrdinaryAnswerIsNotACall() {
        assertTrue(ToolCallParser.parse("It is 3 blocks north of you.").isEmpty());
        assertTrue(ToolCallParser.parse("{\"tool\":\"echo\"}").isEmpty());
        assertTrue(ToolCallParser.parse("{\"tool\":\"test:echo\",\"args\":[1]}").isEmpty());
        assertTrue(ToolCallParser.parse(null).isEmpty());
    }

    @Test
    void aMalformedCallKeepsTheReasonForRepair() {
        ToolCallParser.ParseResult badArgs = ToolCallParser.parseDetailed(
                "{\"tool\":\"test:echo\",\"args\":[]}");
        assertTrue(badArgs.call().isEmpty());
        assertTrue(badArgs.error().contains("args"));

        ToolCallParser.ParseResult badJson = ToolCallParser.parseDetailed("{\"tool\":");
        assertTrue(badJson.call().isEmpty());
        assertFalse(badJson.error().isBlank());
    }

    @Test
    void theCatalogueListsOnlyEnabledTools() {
        ToolSpec enabled = new ToolSpec(ToolId.of("test", "one"), ToolCategory.LOCAL, "does one", new JsonObject(), true);
        ToolSpec disabled = new ToolSpec(ToolId.of("test", "two"), ToolCategory.LOCAL, "does two", null, false);
        String text = ToolCatalogText.describe(List.of(enabled, disabled));
        assertTrue(text.contains("test:one"));
        assertFalse(text.contains("test:two"));
        assertTrue(text.contains("You can call at most one tool"));
    }
}
