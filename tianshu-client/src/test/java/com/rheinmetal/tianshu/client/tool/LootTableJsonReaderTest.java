package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LootTableJsonReaderTest {

    @Test
    void readsItemEntriesAndIgnoresFunctionsAndConditions() {
        JsonElement table = JsonParser.parseString("""
                {
                  "pools": [
                    {
                      "rolls": 1,
                      "entries": [
                        {"type": "minecraft:item", "name": "minecraft:cobblestone"},
                        {"type": "minecraft:item", "name": "minecraft:flint",
                         "functions": [{"function": "minecraft:set_count", "count": 2}]}
                      ],
                      "conditions": [{"condition": "minecraft:survives_explosion"}]
                    }
                  ],
                  "functions": [{"function": "minecraft:apply_bonus"}]
                }
                """);
        Set<String> items = LootTableJsonReader.itemIds(table, id -> null);
        assertEquals(Set.of("minecraft:cobblestone", "minecraft:flint"), items);
    }

    @Test
    void followsNestedLootTablesWithinTheDepthLimit() {
        Map<String, String> tables = Map.of(
                "test:child", "{\"pools\":[{\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"minecraft:diamond\"}]}]}",
                "test:grandchild", "{\"pools\":[{\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"minecraft:emerald\"}]}]}");
        JsonElement table = JsonParser.parseString("""
                {"pools":[{"entries":[
                  {"type": "minecraft:loot_table", "value": "test:child"},
                  {"type": "minecraft:item", "name": "minecraft:stone"}
                ]}]}
                """);
        Map<String, JsonElement> parsed = new java.util.HashMap<>();
        tables.forEach((id, json) -> parsed.put(id, JsonParser.parseString(json)));

        Set<String> items = LootTableJsonReader.itemIds(table, parsed::get);
        assertEquals(Set.of("minecraft:stone", "minecraft:diamond"), items);
    }

    @Test
    void aMissingReferenceIsSimplySkipped() {
        JsonElement table = JsonParser.parseString("""
                {"pools":[{"entries":[{"type": "minecraft:loot_table", "value": "missing:table"}]}]}
                """);
        assertTrue(LootTableJsonReader.itemIds(table, id -> null).isEmpty());
    }

    @Test
    void aFieldWrittenInTheWrongShapeCostsThatEntryAndNothingElse() {
        // Legal JSON, wrong shape: an object where a string belongs. Reading it with getAsString() threw out of the
        // reader, so a single odd entry in a datapack cost the whole loot table.
        JsonElement table = JsonParser.parseString("""
                {"pools":[{"entries":[
                  {"type": {"value": "minecraft:item"}, "name": "minecraft:lost"},
                  {"type": "minecraft:item", "name": {"value": "minecraft:lost_too"}},
                  {"type": "minecraft:item", "name": "minecraft:cobblestone"}
                ]}]}
                """);

        assertEquals(Set.of("minecraft:cobblestone"), LootTableJsonReader.itemIds(table, id -> null));
    }

    @Test
    void aResolverThatThrowsDoesNotLoseTheRestOfTheTable() {
        JsonElement table = JsonParser.parseString("""
                {"pools":[{"entries":[
                  {"type": "minecraft:loot_table", "value": "broken:table"},
                  {"type": "minecraft:item", "name": "minecraft:stone"}
                ]}]}
                """);

        Set<String> items = LootTableJsonReader.itemIds(table, id -> {
            throw new IllegalStateException("cannot read " + id);
        });

        assertEquals(Set.of("minecraft:stone"), items);
    }
}