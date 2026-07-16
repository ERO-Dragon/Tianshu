package com.rheinmetal.tianshu.function.ia.registry;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueParticipantRegistryTest {
    @Test
    void registerReplacesParticipantByModuleAndParticipantId() {
        DialogueParticipantRegistry registry = new DialogueParticipantRegistry();
        registry.register(descriptor("module.a", "p", 1));
        registry.register(descriptor("module.a", "p", 2));

        assertEquals(1, registry.snapshot().size());
        assertEquals(2, registry.find("module.a", "p").orElseThrow().priority());
    }

    @Test
    void unregisterModuleRemovesOnlyOwnedParticipants() {
        DialogueParticipantRegistry registry = new DialogueParticipantRegistry();
        registry.register(descriptor("module.a", "p1", 1));
        registry.register(descriptor("module.b", "p2", 1));

        registry.unregisterModule("module.a");

        assertTrue(registry.find("module.a", "p1").isEmpty());
        assertTrue(registry.find("module.b", "p2").isPresent());
    }

    @Test
    void unregisterParticipantRemovesOnlyExactParticipant() {
        DialogueParticipantRegistry registry = new DialogueParticipantRegistry();
        registry.register(descriptor("module.a", "p1", 1));
        registry.register(descriptor("module.a", "p2", 1));

        var removed = registry.unregister("module.a", "p1");

        assertTrue(removed.isPresent());
        assertTrue(registry.find("module.a", "p1").isEmpty());
        assertTrue(registry.find("module.a", "p2").isPresent());
    }

    private DialogueParticipantDescriptor descriptor(String moduleId, String participantId, int priority) {
        return new DialogueParticipantDescriptor(participantId, moduleId, participantId, priority, com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile.DISABLED, com.rheinmetal.tianshu.protocol.dialogue.model.DialogueVoiceTriggerGroup.EMPTY, "ROUTE", DialogueTurnProcessingPolicy.DEFAULT);
    }
}
