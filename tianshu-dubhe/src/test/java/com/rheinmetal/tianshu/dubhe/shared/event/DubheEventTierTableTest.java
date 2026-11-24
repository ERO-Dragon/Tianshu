package com.rheinmetal.tianshu.dubhe.shared.event;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheEventTierTableTest {

    @Test
    void builtinTableShipsTheDocumentedKinds() {
        DubheEventTierTable table = DubheEventTierTable.builtin();

        assertEquals(42, table.size(), "chat is a presence world event type, and biome change has no exact source");
        assertEquals(9, table.count(DubheEventTier.RARE));
        assertEquals(12, table.count(DubheEventTier.NORMAL));
        assertEquals(21, table.count(DubheEventTier.FREQUENT));
        assertEquals(15, table.count(DubheEventState.WEAKENED));
        assertEquals(5, table.count(DubheEventState.EMPHASIS), "the shipped emphasis set is the actionable warnings");
        assertEquals(22, table.count(DubheEventState.NORMAL));
    }

    @Test
    void knownKindsResolveTheirDocumentedTierAndState() {
        DubheEventTierTable table = DubheEventTierTable.builtin();

        assertEquals(DubheEventTier.RARE, table.tierOf("tianshu:death").orElseThrow());
        assertEquals(DubheEventState.EMPHASIS, table.stateFor("tianshu:death", DubheEventTier.RARE));
        assertEquals(DubheEventTier.NORMAL, table.tierOf("tianshu:damage_taken").orElseThrow());
        assertEquals(DubheEventState.NORMAL, table.stateFor("tianshu:damage_taken", DubheEventTier.NORMAL),
                "a damage line is context, not an interruption");
        assertEquals(DubheEventTier.FREQUENT, table.tierOf("tianshu:health_change").orElseThrow());
        assertEquals(DubheEventState.NORMAL, table.stateFor("tianshu:health_change", DubheEventTier.FREQUENT));
        assertEquals(DubheEventState.WEAKENED, table.stateFor("tianshu:block_break", DubheEventTier.FREQUENT));
    }

    @Test
    void unknownKindHasNoTierAndIsNormal() {
        DubheEventTierTable table = DubheEventTierTable.builtin();

        assertTrue(table.tierOf("mymod:ritual_complete").isEmpty());
        assertEquals(DubheEventState.NORMAL, table.stateFor("mymod:ritual_complete", DubheEventTier.RARE));
        assertTrue(table.tierOf(null).isEmpty());
        assertEquals(DubheEventState.NORMAL, table.stateFor(null, null));
    }

    @Test
    void tableOrderFollowsTheResourceSoTheDocumentCanBeAudited() {
        List<String> kinds = DubheEventTierTable.builtin().entries().stream().map(DubheEventTierTable.Entry::kind).toList();

        assertEquals("tianshu:death", kinds.get(0));
        assertEquals("tianshu:block_break", kinds.get(kinds.size() - 1));
    }

    @Test
    void aV5EntryCarriesTierAndPerRowStates() {
        JsonObject json = JsonParser.parseString("""
                {
                  "schemaVersion": 5,
                  "kinds": {
                    "mymod:thing": {
                      "tier": "NORMAL",
                      "states": { "NORMAL": "emphasis", "FREQUENT": "normal" }
                    }
                  }
                }
                """).getAsJsonObject();

        DubheEventTierTable table = DubheEventTierTable.fromJson(json);

        assertEquals(DubheEventTier.NORMAL, table.tierOf("mymod:thing").orElseThrow());
        assertEquals(DubheEventState.EMPHASIS, table.stateFor("mymod:thing", DubheEventTier.NORMAL));
        assertEquals(DubheEventState.NORMAL, table.stateFor("mymod:thing", DubheEventTier.FREQUENT));
    }

    @Test
    void malformedEntriesAreRejected() {
        JsonObject wrongVersion = JsonParser.parseString("""
                { "schemaVersion": 2, "kinds": { "mymod:thing": "NORMAL" } }
                """).getAsJsonObject();
        JsonObject noTier = JsonParser.parseString("""
                { "schemaVersion": 5, "kinds": { "mymod:thing": { "states": { "NORMAL": "normal" } } } }
                """).getAsJsonObject();
        JsonObject missingOwnRow = JsonParser.parseString("""
                { "schemaVersion": 5, "kinds": { "mymod:thing": { "tier": "NORMAL",
                  "states": { "FREQUENT": "normal" } } } }
                """).getAsJsonObject();

        assertThrows(IllegalStateException.class, () -> DubheEventTierTable.fromJson(wrongVersion));
        assertThrows(IllegalStateException.class, () -> DubheEventTierTable.fromJson(noTier));
        assertThrows(IllegalStateException.class, () -> DubheEventTierTable.fromJson(missingOwnRow));
    }
}