package com.rheinmetal.tianshu.protocol.payload;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The world-event vocabulary is the wire contract between the host that observes the game and every module that reads
 * the stream.
 *
 * <p>These literals were present when the presence module still owned them; they now live in the api so the host can
 * publish without depending on a feature. Pinning them here makes a rename or a typo a test failure instead of a
 * memory entry that silently never matches. The values are the ones the pre-extraction payload served.</p>
 */
final class WorldEventTypesTest {

    @Test
    void eventNamesKeepTheirWireLiterals() {
        assertLiterals(List.of(
                Map.entry(WorldEventTypes.EVENT_ADVANCEMENT_UNLOCKED, "advancement_unlocked"),
                Map.entry(WorldEventTypes.EVENT_PLAYER_DEATH, "player_death"),
                Map.entry(WorldEventTypes.EVENT_ITEM_CRAFTED, "item_crafted"),
                Map.entry(WorldEventTypes.EVENT_MOB_KILLED, "mob_killed"),
                Map.entry(WorldEventTypes.EVENT_FISHING_CATCH, "fishing_catch"),
                Map.entry(WorldEventTypes.EVENT_TAMED, "tamed"),
                Map.entry(WorldEventTypes.EVENT_BLOCK_BROKEN, "block_broken"),
                Map.entry(WorldEventTypes.EVENT_BLOCK_PLACED, "block_placed"),
                Map.entry(WorldEventTypes.EVENT_CONTAINER_OPEN, "container_open"),
                Map.entry(WorldEventTypes.EVENT_MOUNT, "mount"),
                Map.entry(WorldEventTypes.EVENT_DISMOUNT, "dismount"),
                Map.entry(WorldEventTypes.EVENT_DIMENSION_CHANGED, "dimension_changed"),
                Map.entry(WorldEventTypes.EVENT_EFFECT_GAINED, "effect_gained"),
                Map.entry(WorldEventTypes.EVENT_EFFECT_EXPIRED, "effect_expired"),
                Map.entry(WorldEventTypes.EVENT_RESPAWN, "respawn"),
                Map.entry(WorldEventTypes.EVENT_DAMAGE_TAKEN, "damage_taken"),
                Map.entry(WorldEventTypes.EVENT_EXP_LEVEL, "exp_level"),
                Map.entry(WorldEventTypes.EVENT_FRIEND_JOIN, "friend_join"),
                Map.entry(WorldEventTypes.EVENT_FRIEND_LEAVE, "friend_leave"),
                Map.entry(WorldEventTypes.EVENT_ITEM_PICKUP, "item_pickup"),
                Map.entry(WorldEventTypes.EVENT_TRADE, "trade"),
                Map.entry(WorldEventTypes.EVENT_BOSS_ENGAGE, "boss_engage"),
                Map.entry(WorldEventTypes.EVENT_CHAT_MESSAGE, "chat_message")
        ), 23);
    }

    @Test
    void valueNamesKeepTheirWireLiterals() {
        assertLiterals(List.of(
                Map.entry(WorldEventTypes.VALUE_EFFECT_NAME, "effectName"),
                Map.entry(WorldEventTypes.VALUE_AMOUNT, "amount"),
                Map.entry(WorldEventTypes.VALUE_LEVEL, "level"),
                Map.entry(WorldEventTypes.VALUE_PLAYER_NAME, "playerName"),
                Map.entry(WorldEventTypes.VALUE_SENDER_ID, "senderId"),
                Map.entry(WorldEventTypes.VALUE_SENDER_NAME, "senderName"),
                Map.entry(WorldEventTypes.VALUE_TEXT, "text"),
                Map.entry(WorldEventTypes.VALUE_ADVANCEMENT_ID, "advancementId"),
                Map.entry(WorldEventTypes.VALUE_TITLE, "title"),
                Map.entry(WorldEventTypes.VALUE_DESCRIPTION, "description"),
                Map.entry(WorldEventTypes.VALUE_TYPE, "type"),
                Map.entry(WorldEventTypes.VALUE_ICON_ITEM_ID, "iconItemId"),
                Map.entry(WorldEventTypes.VALUE_ITEM_ID, "itemId"),
                Map.entry(WorldEventTypes.VALUE_ITEM_NAME, "itemName"),
                Map.entry(WorldEventTypes.VALUE_COUNT, "count"),
                Map.entry(WorldEventTypes.VALUE_ENTITY_ID, "entityId"),
                Map.entry(WorldEventTypes.VALUE_ENTITY_NAME, "entityName"),
                Map.entry(WorldEventTypes.VALUE_DEATH_MESSAGE, "deathMessage"),
                Map.entry(WorldEventTypes.VALUE_DIMENSION, "dimension"),
                Map.entry(WorldEventTypes.VALUE_DIMENSION_NAME, "dimensionName"),
                Map.entry(WorldEventTypes.VALUE_BLOCK_ID, "blockId"),
                Map.entry(WorldEventTypes.VALUE_BLOCK_NAME, "blockName")
        ), 22);
    }

    private static void assertLiterals(List<Map.Entry<String, String>> expected, int size) {
        assertEquals(size, expected.size(), "each constant must be listed once");
        for (Map.Entry<String, String> entry : expected) {
            assertEquals(entry.getValue(), entry.getKey(), "wire literal changed");
        }
    }
}
