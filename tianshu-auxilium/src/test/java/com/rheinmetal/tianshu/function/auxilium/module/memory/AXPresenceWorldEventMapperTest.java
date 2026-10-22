package com.rheinmetal.tianshu.function.auxilium.module.memory;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXAttachedWorldEvent;

class AXPresenceWorldEventMapperTest {
    @Test
    void mapsAdvancementPayloadToAttachedWorldEvent() {
        AXScope scope = new AXScope("player", "save:world", "World", AXScopeKind.LOCAL_WORLD, true);
        PresenceWorldEventPayload payload = new PresenceWorldEventPayload(
                "event-1",
                PresenceWorldEventPayload.EVENT_ADVANCEMENT_UNLOCKED,
                "player",
                "minecraft:overworld",
                1234L,
                Map.of(
                        "advancementId", "minecraft:story/mine_stone",
                        "title", "Stone Age",
                        "description", "Mine stone with your new pickaxe",
                        "type", "task",
                        "iconItemId", "minecraft:stone"
                )
        );

        AXAttachedWorldEvent event = new AXPresenceWorldEventMapper().map(scope, payload);

        assertEquals(PresenceWorldEventPayload.EVENT_ADVANCEMENT_UNLOCKED, event.eventType());
        assertEquals("save:world", event.worldId());
        assertEquals("minecraft:overworld", event.dimension());
        assertEquals("minecraft:story/mine_stone", event.nativeId());
        assertTrue(event.text().contains("Stone Age"));
        assertTrue(event.tags().contains("minecraft:stone"));
    }
}
