package com.rheinmetal.tianshu.function.ia.runtime;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueAttentionDecay;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimRule;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueVoiceTriggerGroup;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueVoiceTriggerSynchronizerTest {
    @Test
    void bindUpdateAndUnbindOwnOnlyParticipantModuleRegistrations() {
        VoiceTriggerRegistry resources = new VoiceTriggerRegistry();
        DialogueVoiceTriggerSynchronizer synchronizer = new DialogueVoiceTriggerSynchronizer();

        synchronizer.bind(resources, List.of(participant("酒狐", "farm")));
        assertEquals(List.of("酒狐", "farm"), resources.registeredWords());

        synchronizer.synchronize(List.of(participant("女仆", "maid")));
        assertEquals(List.of("女仆", "maid"), resources.registeredWords());

        synchronizer.unbind();
        assertTrue(resources.registeredWords().isEmpty());
    }

    @Test
    void rebindingRemovesRegistrationsFromPreviousRuntimeResources() {
        VoiceTriggerRegistry previous = new VoiceTriggerRegistry();
        VoiceTriggerRegistry current = new VoiceTriggerRegistry();
        DialogueVoiceTriggerSynchronizer synchronizer = new DialogueVoiceTriggerSynchronizer();
        List<DialogueParticipantDescriptor> participants = List.of(participant("酒狐", "farm"));

        synchronizer.bind(previous, participants);
        synchronizer.bind(current, participants);

        assertTrue(previous.registeredWords().isEmpty());
        assertEquals(List.of("酒狐", "farm"), current.registeredWords());
    }

    @Test
    void synchronizeAfterUnbindDoesNotRestoreStoppedRuntimeRegistrations() {
        VoiceTriggerRegistry resources = new VoiceTriggerRegistry();
        DialogueVoiceTriggerSynchronizer synchronizer = new DialogueVoiceTriggerSynchronizer();

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
                DialogueClaimProfile.rules(DialogueClaimRule.anyStrong(
                        "maid.wake",
                        DialogueAttentionDecay.SLOW,
                        DialogueClaimCondition.wakeWord(wakeWord)
                )),
                DialogueVoiceTriggerGroup.of(List.of(wakeWord), List.of(extraWord)),
                "MAID.DIALOGUE_INPUT",
                DialogueTurnProcessingPolicy.DEFAULT
        );
    }
}
