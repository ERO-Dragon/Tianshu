package com.rheinmetal.tianshu.function.auxilium.event;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXEventTierTableTest {

    @Test
    void builtinTableShipsTheFourtyFourDocumentedKinds() {
        AXEventTierTable table = AXEventTierTable.builtin();

        assertEquals(44, table.size());
        assertEquals(9, table.count(AXEventTier.HIGH));
        assertEquals(13, table.count(AXEventTier.MID));
        assertEquals(22, table.count(AXEventTier.LOW));
        assertEquals(15, table.countBlacklisted());
    }

    @Test
    void everyBlacklistedKindIsLowTier() {
        for (AXEventTierTable.Entry entry : AXEventTierTable.builtin().entries()) {
            if (entry.blacklist()) {
                assertEquals(AXEventTier.LOW, entry.tier(), "blacklisted kind must be low tier: " + entry.kind());
            }
        }
    }

    @Test
    void knownKindsResolveTheirDocumentedTierAndBlacklistFlag() {
        AXEventTierTable table = AXEventTierTable.builtin();

        assertEquals(AXEventTier.HIGH, table.tierOf("tianshu:death").orElseThrow());
        assertFalse(table.blacklisted("tianshu:death"));
        assertEquals(AXEventTier.MID, table.tierOf("tianshu:respawn").orElseThrow());
        assertEquals(AXEventTier.LOW, table.tierOf("tianshu:health_change").orElseThrow());
        assertFalse(table.blacklisted("tianshu:health_change"));
        assertEquals(AXEventTier.LOW, table.tierOf("tianshu:block_break").orElseThrow());
        assertTrue(table.blacklisted("tianshu:block_break"));
        assertEquals(AXEventTier.HIGH, table.tierOf("tianshu:chat_mentioned").orElseThrow());
        assertEquals(AXEventTier.LOW, table.tierOf("tianshu:chat_message").orElseThrow());
    }

    @Test
    void unknownKindHasNoTierAndIsNotBlacklisted() {
        AXEventTierTable table = AXEventTierTable.builtin();

        assertTrue(table.tierOf("mymod:ritual_complete").isEmpty());
        assertFalse(table.blacklisted("mymod:ritual_complete"));
        assertTrue(table.tierOf(null).isEmpty());
        assertFalse(table.blacklisted(null));
    }

    @Test
    void tableOrderFollowsTheResourceSoTheDocumentCanBeAudited() {
        List<String> kinds = AXEventTierTable.builtin().entries().stream().map(AXEventTierTable.Entry::kind).toList();

        assertEquals("tianshu:death", kinds.get(0));
        assertEquals("tianshu:block_break", kinds.get(kinds.size() - 1));
    }

    @Test
    void entryWithoutAValidTierIsRejected() {
        JsonObject json = JsonParser.parseString("""
                { "kinds": { "mymod:thing": { "blacklist": false } } }
                """).getAsJsonObject();

        assertThrows(IllegalStateException.class, () -> AXEventTierTable.fromJson(json));
    }

    /**
     * Wording is not table data: how a folded window reads is declared by the descriptor's own
     * {@link AXEventFold}, so a table row carries a tier and a blacklist flag only.
     */
    @Test
    void tableRowsCarryNoWordingMetadata() {
        JsonObject json = JsonParser.parseString("""
                { "kinds": { "mymod:thing": { "tier": "high", "blacklist": false, "aggregate": "range" } } }
                """).getAsJsonObject();

        AXEventTierTable table = AXEventTierTable.fromJson(json);

        assertEquals(AXEventTier.HIGH, table.tierOf("mymod:thing").orElseThrow());
        assertEquals(1, table.size());
    }
}
