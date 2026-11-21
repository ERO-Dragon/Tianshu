package com.rheinmetal.tianshu.dubhe.module.memory;

import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheMemoryEvent;
import com.rheinmetal.tianshu.dubhe.module.memory.shortterm.DubheStmBlock;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRawTurn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DubheMemorySchemaCompatibilityTest {
    @Test
    void rawTurnReadsOldTimestampAndIgnoresUnknownFields() {
        DubheRawTurn turn = DubheRawTurn.fromJson(JsonParser.parseString("""
                {
                  "id": "raw-old",
                  "role": "user",
                  "content": "remember me",
                  "createdAt": 100,
                  "worldId": "world-a",
                  "futureField": {"ignored": true}
                }
                """).getAsJsonObject());

        assertEquals("raw-old", turn.id());
        assertEquals(100L, turn.createdAtMillis());
        assertEquals("remember me", turn.content());
    }

    @Test
    void stmBlockReadsOldSourceRangeFields() {
        DubheStmBlock block = DubheStmBlock.fromJson(JsonParser.parseString("""
                {
                  "id": "stm-old",
                  "worldId": "world-a",
                  "createdAt": 200,
                  "fromTurnCreatedAt": 100,
                  "toTurnCreatedAt": 190,
                  "content": "older summary"
                }
                """).getAsJsonObject());

        assertEquals(100L, block.sourceFromMillis());
        assertEquals(190L, block.sourceToMillis());
        assertEquals("older summary", block.content());
    }

    @Test
    void memoryEventReadsOldEventTimestamp() {
        DubheMemoryEvent event = DubheMemoryEvent.fromJson(JsonParser.parseString("""
                {
                  "id": "event-old",
                  "fact": "the player found a village",
                  "worldId": "world-a",
                  "createdAtMillis": 300,
                  "eventAtMillis": 250,
                  "unknownList": [1, 2, 3]
                }
                """).getAsJsonObject());

        assertEquals(250L, event.happenedAtMillis());
        assertEquals("the player found a village", event.fact());
    }
}
