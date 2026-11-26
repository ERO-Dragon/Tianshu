package com.rheinmetal.tianshu.dubhe;

import com.rheinmetal.tianshu.api.capability.CapabilityId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DubhePlayerSubscriptionTest {
    @Test
    void subscribesToThePlayerManualAndExplicitPlayerToolsOnly() {
        var rule = DubheModule.playerToolSubscription();
        var ids = rule.namedIds();
        assertTrue(ids.contains(CapabilityId.parse("tianshu:player-tools")));
        assertTrue(ids.contains(CapabilityId.parse("minecraft:container.transfer")));
        assertTrue(ids.contains(CapabilityId.parse("minecraft:world.survey")));
        assertFalse(ids.contains(CapabilityId.parse("tianshu:entity-tools")));
        assertFalse(ids.stream().anyMatch(id -> id.name().startsWith("entity.")));
        assertFalse(rule.allProviders());
        assertTrue(rule.providers().isEmpty());
    }
}
