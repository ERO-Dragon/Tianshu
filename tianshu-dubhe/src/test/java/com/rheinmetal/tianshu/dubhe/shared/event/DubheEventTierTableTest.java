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

        assertEquals(40, table.size(), "chat is a presence world event type, and biome change has no exact source");
        assertEquals(8, table.count(DubheEventTier.RARE));
        assertEquals(12, table.count(DubheEventTier.NORMAL));
        assertEquals(20, table.count(DubheEventTier.FREQUENT));
        assertEquals(14, table.count(DubheEventList.BLACKLIST));
        assertEquals(5, table.count(DubheEventList.WHITELIST), "the shipped whitelist is the actionable warnings");
        assertEquals(21, table.count(DubheEventList.NEUTRAL));
    }

    @Test
    void everyBlacklistedKindIsFrequentTier() {
        // The data has not changed, only the names: every kind the player asked to hear less of is a frequent one.
        for (DubheEventTierTable.Entry entry : DubheEventTierTable.builtin().entries()) {
            if (entry.list() == DubheEventList.BLACKLIST) {
                assertEquals(DubheEventTier.FREQUENT, entry.tier(), "blacklisted kind must be frequent: " + entry.kind());
            }
        }
    }

    @Test
    void knownKindsResolveTheirDocumentedTierAndListState() {
        DubheEventTierTable table = DubheEventTierTable.builtin();

        assertEquals(DubheEventTier.RARE, table.tierOf("tianshu:death").orElseThrow());
        assertEquals(DubheEventList.WHITELIST, table.listOf("tianshu:death"));
        assertEquals(DubheEventTier.NORMAL, table.tierOf("tianshu:damage_taken").orElseThrow());
        assertEquals(DubheEventList.NEUTRAL, table.listOf("tianshu:damage_taken"),
                "a damage line is context, not an interruption");
        assertEquals(DubheEventTier.FREQUENT, table.tierOf("tianshu:health_change").orElseThrow());
        assertEquals(DubheEventList.NEUTRAL, table.listOf("tianshu:health_change"));
        assertEquals(DubheEventTier.FREQUENT, table.tierOf("tianshu:block_break").orElseThrow());
        assertEquals(DubheEventList.BLACKLIST, table.listOf("tianshu:block_break"));
        assertTrue(table.blacklisted("tianshu:block_break"));
    }

    @Test
    void unknownKindHasNoTierAndIsNeutral() {
        DubheEventTierTable table = DubheEventTierTable.builtin();

        assertTrue(table.tierOf("mymod:ritual_complete").isEmpty());
        assertEquals(DubheEventList.NEUTRAL, table.listOf("mymod:ritual_complete"));
        assertTrue(table.tierOf(null).isEmpty());
        assertEquals(DubheEventList.NEUTRAL, table.listOf(null));
    }

    @Test
    void tableOrderFollowsTheResourceSoTheDocumentCanBeAudited() {
        List<String> kinds = DubheEventTierTable.builtin().entries().stream().map(DubheEventTierTable.Entry::kind).toList();

        assertEquals("tianshu:death", kinds.get(0));
        assertEquals("tianshu:block_break", kinds.get(kinds.size() - 1));
    }

    @Test
    void entryWithoutAValidTierIsRejected() {
        JsonObject noTier = JsonParser.parseString("""
                { "kinds": { "mymod:thing": { "list": "neutral" } } }
                """).getAsJsonObject();
        JsonObject unknownTier = JsonParser.parseString("""
                { "kinds": { "mymod:thing": "HIGH" } }
                """).getAsJsonObject();

        assertThrows(IllegalStateException.class, () -> DubheEventTierTable.fromJson(noTier));
        assertThrows(IllegalStateException.class, () -> DubheEventTierTable.fromJson(unknownTier),
                "the retired tier names must not parse any more");
    }

    /**
     * The list is its own section: a kind the list mentions but the tier table does not still gets the list step, and
     * its tier stays unknown so the dispatcher applies its conservative fallback.
     */
    @Test
    void theListIsParsedSeparatelyFromTheTiers() {
        JsonObject json = JsonParser.parseString("""
                {
                  "kinds": { "mymod:thing": "NORMAL", "mymod:other": "RARE" },
                  "list": { "mymod:thing": "whitelist", "mymod:other": "blacklist" }
                }
                """).getAsJsonObject();

        DubheEventTierTable table = DubheEventTierTable.fromJson(json);

        assertEquals(2, table.size());
        assertEquals(DubheEventTier.NORMAL, table.tierOf("mymod:thing").orElseThrow());
        assertEquals(DubheEventList.WHITELIST, table.listOf("mymod:thing"));
        assertEquals(DubheEventList.BLACKLIST, table.listOf("mymod:other"));
        assertEquals(1, table.count(DubheEventList.WHITELIST));
        assertEquals(1, table.count(DubheEventList.BLACKLIST));
    }

    @Test
    void aListedKindWithoutATierKeepsOnlyItsListStep() {
        JsonObject json = JsonParser.parseString("""
                {
                  "kinds": { "mymod:thing": "NORMAL" },
                  "list": { "mymod:unlisted": "blacklist" }
                }
                """).getAsJsonObject();

        DubheEventTierTable table = DubheEventTierTable.fromJson(json);

        assertTrue(table.tierOf("mymod:unlisted").isEmpty(), "no tier is invented for a listed-only kind");
        assertEquals(DubheEventList.BLACKLIST, table.listOf("mymod:unlisted"));
    }
}
