package com.rheinmetal.tianshu.protocol.voice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceTriggerRegistryTest {
    @Test
    void reportsExactWordConflictsWithoutRejectingRegistration() {
        VoiceTriggerRegistry registry = new VoiceTriggerRegistry();
        registry.register(new VoiceTriggerRegistration("module.left", List.of("酒狐"), List.of()));

        VoiceTriggerRegistrationResult result = registry.register(new VoiceTriggerRegistration("module.right", List.of("酒狐"), List.of()));

        assertTrue(result.accepted());
        assertEquals(1, result.conflicts().size());
        assertEquals("酒狐", result.conflicts().get(0).word());
        assertEquals(List.of("module.left", "module.right"), result.conflicts().get(0).moduleIds());
    }

    @Test
    void reportsContainmentConflictsAsRegistrationWarning() {
        VoiceTriggerRegistry registry = new VoiceTriggerRegistry();
        registry.register(new VoiceTriggerRegistration("module.short", List.of("酒狐"), List.of()));

        VoiceTriggerRegistrationResult result = registry.register(new VoiceTriggerRegistration("module.long", List.of("酒狐女仆"), List.of()));

        assertTrue(result.accepted());
        assertEquals(1, result.conflicts().size());
        assertEquals("酒狐 / 酒狐女仆", result.conflicts().get(0).word());
        assertEquals(List.of("module.short", "module.long"), result.conflicts().get(0).moduleIds());
    }

    @Test
    void containmentConflictAlsoConsidersExtraWords() {
        VoiceTriggerRegistry registry = new VoiceTriggerRegistry();
        registry.register(new VoiceTriggerRegistration("module.left", List.of("女仆"), List.of("种地")));

        VoiceTriggerRegistrationResult result = registry.register(new VoiceTriggerRegistration("module.right", List.of("酒狐女仆"), List.of()));

        assertTrue(result.accepted());
        assertEquals(1, result.conflicts().size());
        assertEquals("女仆 / 酒狐女仆", result.conflicts().get(0).word());
    }
}
