package com.rheinmetal.tianshu.dubhe.module.memory;

import com.rheinmetal.tianshu.presence.payload.PresenceWorldEventPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheAttachedWorldEvent;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheMemoryEvent;
import com.rheinmetal.tianshu.dubhe.module.memory.shortterm.DubheStmBlock;

class DubheWorldEventMemoryLinkerTest {
    @Test
    void directWorldEventsBecomeEventsOwnedByTheStm() {
        DubheStmBlock stm = new DubheStmBlock("", "", "world", 1000L, 900L, 950L, "", "", 1, 0, "一段 STM。", List.of());
        DubheAttachedWorldEvent advancement = new DubheAttachedWorldEvent(
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
        DubheWorldEventMemoryLinker linker = new DubheWorldEventMemoryLinker(null);

        List<DubheMemoryEvent> events = linker.directEventsFor(stm.id(), List.of(advancement));

        assertEquals(1, events.size());
        assertEquals(stm.id(), events.get(0).stmId());
        assertEquals("world_advancement_unlocked", events.get(0).sourceKind());
    }

    @Test
    void attachOnlyWorldEventsDoNotBecomeDirectEvents() {
        DubheStmBlock stm = new DubheStmBlock("", "", "world", 1000L, 900L, 950L, "", "", 1, 0, "一段 STM。", List.of());
        DubheAttachedWorldEvent death = new DubheAttachedWorldEvent(
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
        DubheWorldEventMemoryLinker linker = new DubheWorldEventMemoryLinker(null);

        assertEquals(List.of(death.id()), linker.attachedEventIds(List.of(death)));
        assertEquals(0, linker.directEventsFor(stm.id(), List.of(death)).size());
    }
}
