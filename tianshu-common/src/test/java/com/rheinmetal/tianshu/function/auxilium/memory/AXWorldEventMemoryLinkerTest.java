package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AXWorldEventMemoryLinkerTest {
    @Test
    void directWorldEventsBecomeEventsOwnedByTheStm() {
        AXStmBlock stm = new AXStmBlock("", "", "world", 1000L, 900L, 950L, "", "", 1, 0, "一段 STM。", List.of());
        AXAttachedWorldEvent advancement = new AXAttachedWorldEvent(
                "",
                PresenceWorldEventPayload.EVENT_ADVANCEMENT_UNLOCKED,
                "",
                "world",
                "minecraft:overworld",
                "",
                940L,
                "minecraft:story/mine_stone",
                "advancement_unlocked: Stone Age",
                List.of("minecraft:story/mine_stone")
        );
        AXWorldEventMemoryLinker linker = new AXWorldEventMemoryLinker(null);

        List<AXMemoryEvent> events = linker.directEventsFor(stm, List.of(advancement));

        assertEquals(1, events.size());
        assertEquals(stm.id(), events.get(0).stmId());
        assertEquals("world_advancement_unlocked", events.get(0).sourceKind());
    }

    @Test
    void attachOnlyWorldEventsDoNotBecomeDirectEvents() {
        AXStmBlock stm = new AXStmBlock("", "", "world", 1000L, 900L, 950L, "", "", 1, 0, "一段 STM。", List.of());
        AXAttachedWorldEvent death = new AXAttachedWorldEvent(
                "",
                "player_death",
                "",
                "world",
                "minecraft:overworld",
                "",
                940L,
                "",
                "player_death",
                List.of()
        );
        AXWorldEventMemoryLinker linker = new AXWorldEventMemoryLinker(null);

        assertEquals(List.of(death.id()), linker.attachedEventIds(List.of(death)));
        assertEquals(0, linker.directEventsFor(stm, List.of(death)).size());
    }
}
