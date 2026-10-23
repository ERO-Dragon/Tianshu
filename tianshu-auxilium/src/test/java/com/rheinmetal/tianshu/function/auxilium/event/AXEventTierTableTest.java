package com.rheinmetal.tianshu.function.auxilium.event;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXEventTierTableTest {

    @Test
    void builtinTableShipsTheFourtyFourDocumentedKinds() {
        AXEventTierTable table = AXEventTierTable.builtin();

        assertEquals(44, table.size());
        assertEquals(9, table.count(AXEventTier.RARE));
        assertEquals(13, table.count(AXEventTier.NORMAL));
        assertEquals(22, table.count(AXEventTier.FREQUENT));
        assertEquals(15, table.count(AXEventList.BLACKLIST));
        assertEquals(6, table.count(AXEventList.WHITELIST), "the shipped whitelist is the actionable warnings");
        assertEquals(23, table.count(AXEventList.NEUTRAL));
    }

    @Test
    void everyBlacklistedKindIsFrequentTier() {
        // The data has not changed, only the names: every kind the player asked to hear less of is a frequent one.
        for (AXEventTierTable.Entry entry : AXEventTierTable.builtin().entries()) {
            if (entry.list() == AXEventList.BLACKLIST) {
                assertEquals(AXEventTier.FREQUENT, entry.tier(), "blacklisted kind must be frequent: " + entry.kind());
            }
        }
    }

    @Test
    void knownKindsResolveTheirDocumentedTierAndListState() {
        AXEventTierTable table = AXEventTierTable.builtin();

        assertEquals(AXEventTier.RARE, table.tierOf("tianshu:death").orElseThrow());
        assertEquals(AXEventList.WHITELIST, table.listOf("tianshu:death"));
        assertEquals(AXEventTier.NORMAL, table.tierOf("tianshu:respawn").orElseThrow());
        assertEquals(AXEventList.NEUTRAL, table.listOf("tianshu:respawn"),
                "a respawn right after a death would only repeat what was just said");
        assertEquals(AXEventTier.FREQUENT, table.tierOf("tianshu:health_change").orElseThrow());
        assertEquals(AXEventList.NEUTRAL, table.listOf("tianshu:health_change"));
        assertEquals(AXEventTier.FREQUENT, table.tierOf("tianshu:block_break").orElseThrow());
        assertEquals(AXEventList.BLACKLIST, table.listOf("tianshu:block_break"));
        assertTrue(table.blacklisted("tianshu:block_break"));
    }

    @Test
    void unknownKindHasNoTierAndIsNeutral() {
        AXEventTierTable table = AXEventTierTable.builtin();

        assertTrue(table.tierOf("mymod:ritual_complete").isEmpty());
        assertEquals(AXEventList.NEUTRAL, table.listOf("mymod:ritual_complete"));
        assertTrue(table.tierOf(null).isEmpty());
        assertEquals(AXEventList.NEUTRAL, table.listOf(null));
    }

    @Test
    void tableOrderFollowsTheResourceSoTheDocumentCanBeAudited() {
        List<String> kinds = AXEventTierTable.builtin().entries().stream().map(AXEventTierTable.Entry::kind).toList();

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

        assertThrows(IllegalStateException.class, () -> AXEventTierTable.fromJson(noTier));
        assertThrows(IllegalStateException.class, () -> AXEventTierTable.fromJson(unknownTier),
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

        AXEventTierTable table = AXEventTierTable.fromJson(json);

        assertEquals(2, table.size());
        assertEquals(AXEventTier.NORMAL, table.tierOf("mymod:thing").orElseThrow());
        assertEquals(AXEventList.WHITELIST, table.listOf("mymod:thing"));
        assertEquals(AXEventList.BLACKLIST, table.listOf("mymod:other"));
        assertEquals(1, table.count(AXEventList.WHITELIST));
        assertEquals(1, table.count(AXEventList.BLACKLIST));
    }

    @Test
    void aListedKindWithoutATierKeepsOnlyItsListStep() {
        JsonObject json = JsonParser.parseString("""
                {
                  "kinds": { "mymod:thing": "NORMAL" },
                  "list": { "mymod:unlisted": "blacklist" }
                }
                """).getAsJsonObject();

        AXEventTierTable table = AXEventTierTable.fromJson(json);

        assertTrue(table.tierOf("mymod:unlisted").isEmpty(), "no tier is invented for a listed-only kind");
        assertEquals(AXEventList.BLACKLIST, table.listOf("mymod:unlisted"));
    }
}
