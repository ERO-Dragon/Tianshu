package com.rheinmetal.tianshu.function.auxilium.module.memory;

import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlock;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AXMemorySchemaCompatibilityTest {
    @Test
    void rawTurnReadsOldTimestampAndIgnoresUnknownFields() {
        AXRawTurn turn = AXRawTurn.fromJson(JsonParser.parseString("""
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
        AXStmBlock block = AXStmBlock.fromJson(JsonParser.parseString("""
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
        AXMemoryEvent event = AXMemoryEvent.fromJson(JsonParser.parseString("""
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
