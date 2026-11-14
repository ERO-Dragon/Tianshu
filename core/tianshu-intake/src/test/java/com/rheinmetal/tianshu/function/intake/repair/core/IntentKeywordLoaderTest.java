package com.rheinmetal.tianshu.function.intake.repair.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentKeywordLoaderTest {

    @Test
    void bundledTableLoadsWithTheKeysTheParserAndPreprocessorRead() {
        Map<String, String[]> table = IntentKeywordLoader.load();

        assertFalse(table.isEmpty(),
                "the bundled table must be reachable from the loader; an empty table silently disables"
                        + " command splitting, negation, filler and boundary-word removal");
        for (String key : List.of(
                "PRIORITY_SPLITTERS", "PARALLEL_SPLITTERS", "NEGATIONS", "FILLER_WORDS", "ENTITY_BOUNDARY_WORDS")) {
            assertTrue(table.containsKey(key), key + " missing from " + table.keySet());
            assertTrue(table.get(key).length > 0, key + " must not be empty");
        }
    }

    @Test
    void parsingDropsBlankKeywordsAndEmptyGroups() {
        String json = "{\"FILLER_WORDS\":[\"帮我\",\"\",\"   \",\"帮我\"],"
                + "\"NEGATIONS\":[],"
                + "\"PARALLEL_SPLITTERS\":null,"
                + "\"ENTITY_BOUNDARY_WORDS\":[null,\"丢掉\"]}";

        Map<String, String[]> table = IntentKeywordLoader.parse(stream(json));

        assertTrue(table.containsKey("FILLER_WORDS"));
        assertEquals(List.of("帮我"), List.of(table.get("FILLER_WORDS")),
                "blank entries would make the parser split on an empty string or replace a null keyword");
        assertFalse(table.containsKey("NEGATIONS"));
        assertFalse(table.containsKey("PARALLEL_SPLITTERS"));
        assertEquals(List.of("丢掉"), List.of(table.get("ENTITY_BOUNDARY_WORDS")));
    }

    @Test
    void malformedOverrideParsesToNothingInsteadOfFailingTheLane() {
        assertTrue(IntentKeywordLoader.parse(stream("{ not json")).isEmpty());
        assertTrue(IntentKeywordLoader.parse(null).isEmpty());
    }

    private static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}