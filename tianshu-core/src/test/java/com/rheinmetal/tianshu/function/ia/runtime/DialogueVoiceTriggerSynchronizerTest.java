package com.rheinmetal.tianshu.function.ia.runtime;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueAttentionDecay;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimRule;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueVoiceTriggerGroup;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceAccess;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceSnapshot;
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
        TestVoiceResources resources = new TestVoiceResources();
        DialogueVoiceTriggerSynchronizer synchronizer = new DialogueVoiceTriggerSynchronizer();

        synchronizer.bind(resources, List.of(participant("酒狐", "farm")));
        assertEquals(List.of("酒狐", "farm"), resources.voiceTriggers().asrHotwords());

        synchronizer.synchronize(List.of(participant("女仆", "maid")));
        assertEquals(List.of("女仆", "maid"), resources.voiceTriggers().asrHotwords());

        synchronizer.unbind();
        assertTrue(resources.voiceTriggers().asrHotwords().isEmpty());
    }

    @Test
    void rebindingRemovesRegistrationsFromPreviousRuntimeResources() {
        TestVoiceResources previous = new TestVoiceResources();
        TestVoiceResources current = new TestVoiceResources();
        DialogueVoiceTriggerSynchronizer synchronizer = new DialogueVoiceTriggerSynchronizer();
        List<DialogueParticipantDescriptor> participants = List.of(participant("酒狐", "farm"));

        synchronizer.bind(previous, participants);
        synchronizer.bind(current, participants);

        assertTrue(previous.voiceTriggers().asrHotwords().isEmpty());
        assertEquals(List.of("酒狐", "farm"), current.voiceTriggers().asrHotwords());
    }

    @Test
    void synchronizeAfterUnbindDoesNotRestoreStoppedRuntimeRegistrations() {
        TestVoiceResources resources = new TestVoiceResources();
        DialogueVoiceTriggerSynchronizer synchronizer = new DialogueVoiceTriggerSynchronizer();

        synchronizer.bind(resources, List.of(participant("酒狐", "farm")));
        synchronizer.unbind();
        synchronizer.synchronize(List.of(participant("女仆", "maid")));

        assertTrue(resources.voiceTriggers().asrHotwords().isEmpty());
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

    private static final class TestVoiceResources implements VoiceResourceAccess {
        private final VoiceTriggerRegistry triggers = new VoiceTriggerRegistry();

        @Override
        public VoiceTriggerRegistry voiceTriggers() {
            return triggers;
        }

        @Override
        public VoiceResourceSnapshot snapshot() {
            return new VoiceResourceSnapshot(0L, Path.of("zh"), Path.of("en"), triggers);
        }

        @Override
        public Path resolveHotwordsFile(String language) {
            return Path.of(language == null ? "" : language);
        }

        @Override
        public void addChangeListener(Consumer<VoiceResourceSnapshot> listener) {
        }

        @Override
        public void removeChangeListener(Consumer<VoiceResourceSnapshot> listener) {
        }
    }
}
