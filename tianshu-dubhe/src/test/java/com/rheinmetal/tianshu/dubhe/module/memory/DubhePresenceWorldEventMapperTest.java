package com.rheinmetal.tianshu.dubhe.module.memory;

import com.rheinmetal.tianshu.dubhe.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.scope.DubheScopeKind;
import com.rheinmetal.tianshu.presence.payload.PresenceWorldEventPayload;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheAttachedWorldEvent;

class DubhePresenceWorldEventMapperTest {
    @Test
    void mapsAdvancementPayloadToAttachedWorldEvent() {
        DubheScope scope = new DubheScope("player", "save:world", "World", DubheScopeKind.LOCAL_WORLD, true);
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

        DubheAttachedWorldEvent event = new DubhePresenceWorldEventMapper().map(scope, payload);

        assertEquals(PresenceWorldEventPayload.EVENT_ADVANCEMENT_UNLOCKED, event.eventType());
        assertEquals("save:world", event.worldId());
        assertEquals("minecraft:overworld", event.dimension());
        assertEquals("minecraft:story/mine_stone", event.nativeId());
        assertTrue(event.text().contains("Stone Age"));
        assertTrue(event.tags().contains("minecraft:stone"));
    }
}
