package com.rheinmetal.tianshu.protocol.trigger;

import com.rheinmetal.tianshu.protocol.payload.TriggerRegistryPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriggerRegistryTest {
    @Test
    void triggerContractDoesNotDuplicateIaParticipantPriority() {
        assertFalse(Arrays.stream(TriggerRegistration.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("priority")));
        assertFalse(Arrays.stream(com.rheinmetal.tianshu.protocol.payload.TriggerRegistryPayload.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("priority")));
    }

    @Test
    void reportsExactWordConflictsWithoutRejectingRegistration() {
        TriggerRegistry registry = new TriggerRegistry();
        registry.register(new TriggerRegistration("module.left", List.of("酒狐"), List.of()));

        TriggerRegistrationResult result = registry.register(new TriggerRegistration("module.right", List.of("酒狐"), List.of()));

        assertTrue(result.accepted());
        assertEquals(1, result.conflicts().size());
        assertEquals("酒狐", result.conflicts().get(0).word());
        assertEquals(List.of("module.left", "module.right"), result.conflicts().get(0).moduleIds());
    }

    @Test
    void reportsContainmentConflictsAsRegistrationWarning() {
        TriggerRegistry registry = new TriggerRegistry();
        registry.register(new TriggerRegistration("module.short", List.of("酒狐"), List.of()));

        TriggerRegistrationResult result = registry.register(new TriggerRegistration("module.long", List.of("酒狐女仆"), List.of()));

        assertTrue(result.accepted());
        assertEquals(1, result.conflicts().size());
        assertEquals("酒狐 / 酒狐女仆", result.conflicts().get(0).word());
        assertEquals(List.of("module.short", "module.long"), result.conflicts().get(0).moduleIds());
    }

    @Test
    void containmentConflictAlsoConsidersExtraWords() {
        TriggerRegistry registry = new TriggerRegistry();
        registry.register(new TriggerRegistration("module.left", List.of("女仆"), List.of("种地")));

        TriggerRegistrationResult result = registry.register(new TriggerRegistration("module.right", List.of("酒狐女仆"), List.of()));

        assertTrue(result.accepted());
        assertEquals(1, result.conflicts().size());
        assertEquals("女仆 / 酒狐女仆", result.conflicts().get(0).word());
    }
}
