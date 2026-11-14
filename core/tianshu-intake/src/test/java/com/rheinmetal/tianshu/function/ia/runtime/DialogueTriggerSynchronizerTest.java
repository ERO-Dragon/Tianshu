package com.rheinmetal.tianshu.function.ia.runtime;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimRule;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTriggerGroup;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueTriggerSynchronizerTest {
    @Test
    void bindUpdateAndUnbindOwnOnlyParticipantModuleRegistrations() {
        TriggerRegistry resources = new TriggerRegistry();
        DialogueTriggerSynchronizer synchronizer = new DialogueTriggerSynchronizer();

        synchronizer.bind(resources, List.of(participant("酒狐", "farm")));
        assertEquals(List.of("酒狐", "farm"), resources.registeredWords());

        synchronizer.synchronize(List.of(participant("女仆", "maid")));
        assertEquals(List.of("女仆", "maid"), resources.registeredWords());

        synchronizer.unbind();
        assertTrue(resources.registeredWords().isEmpty());
    }

    @Test
    void rebindingRemovesRegistrationsFromPreviousRuntimeResources() {
        TriggerRegistry previous = new TriggerRegistry();
        TriggerRegistry current = new TriggerRegistry();
        DialogueTriggerSynchronizer synchronizer = new DialogueTriggerSynchronizer();
        List<DialogueParticipantDescriptor> participants = List.of(participant("酒狐", "farm"));

        synchronizer.bind(previous, participants);
        synchronizer.bind(current, participants);

        assertTrue(previous.registeredWords().isEmpty());
        assertEquals(List.of("酒狐", "farm"), current.registeredWords());
    }

    @Test
    void synchronizeAfterUnbindDoesNotRestoreStoppedRuntimeRegistrations() {
        TriggerRegistry resources = new TriggerRegistry();
        DialogueTriggerSynchronizer synchronizer = new DialogueTriggerSynchronizer();

        synchronizer.bind(resources, List.of(participant("酒狐", "farm")));
        synchronizer.unbind();
        synchronizer.synchronize(List.of(participant("女仆", "maid")));

        assertTrue(resources.registeredWords().isEmpty());
    }

    private DialogueParticipantDescriptor participant(String wakeWord, String extraWord) {
        return new DialogueParticipantDescriptor(
                "maid",
                "module.maid",
                "Maid",
                10,
                DialogueClaimProfile.rules(DialogueClaimRule.any(
                        "maid.wake",
                        DialogueClaimCondition.wakeWord(wakeWord)
                )),
                DialogueTriggerGroup.of(List.of(wakeWord), List.of(extraWord)),
                "MAID.DIALOGUE_INPUT",
                DialogueTurnProcessingPolicy.DEFAULT
        );
    }
}
