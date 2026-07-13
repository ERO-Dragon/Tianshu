package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.ia.model.DialogueAttentionDecay;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimRule;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.function.ia.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.function.ia.model.DialogueVoiceTriggerGroup;
import com.rheinmetal.tianshu.function.ia.payload.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueParticipantUnregisterPayload;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AXParticipantRegistrar {
    public static final String PARTICIPANT_ID = "tianshu.AX";
    public static final String DISPLAY_NAME = "辅星";
    private final AXProtocolAdapter adapter;
    private final AXAssistantSettings settings;
    private final AtomicBoolean registered = new AtomicBoolean(false);

    public AXParticipantRegistrar(AXProtocolAdapter adapter, AXAssistantSettings settings) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.settings = settings == null ? AXAssistantSettings.DEFAULT : settings;
    }

    public void register() {
        if (adapter.dialogueParticipantRegistrationProviderCount() <= 0) {
            return;
        }
        if (!registered.compareAndSet(false, true)) {
            return;
        }
        adapter.registerDialogueParticipant(new DialogueParticipantRegisterPayload(new DialogueParticipantDescriptor(
                PARTICIPANT_ID,
                AXModule.MODULE_ID,
                DISPLAY_NAME,
                0,
                List.of(),
                List.of(),
                List.of(),
                claimProfile(),
                voiceTriggerGroup(),
                AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY,
                DialogueTurnProcessingPolicy.DEFAULT
        ), System.currentTimeMillis()));
    }

    public void unregister() {
        if (!registered.compareAndSet(true, false)) {
            return;
        }
        adapter.unregisterDialogueParticipant(new DialogueParticipantUnregisterPayload(
                AXModule.MODULE_ID,
                PARTICIPANT_ID,
                false,
                System.currentTimeMillis()
        ));
    }

    private DialogueClaimProfile claimProfile() {
        List<String> wakeWords = wakeWords();
        if (wakeWords.isEmpty()) {
            return DialogueClaimProfile.DEFAULT_OWNER;
        }
        return DialogueClaimProfile.defaultOwnerWithRules(DialogueClaimRule.anyStrong(
                "ax.wake_word",
                DialogueAttentionDecay.SLOW,
                wakeWords.stream().map(DialogueClaimCondition::wakeWord).toArray(DialogueClaimCondition[]::new)
        ));
    }

    private DialogueVoiceTriggerGroup voiceTriggerGroup() {
        return DialogueVoiceTriggerGroup.of(wakeWords(), List.of());
    }

    private List<String> wakeWords() {
        String wakeWord = settings.wakeWord();
        if (wakeWord == null || wakeWord.isBlank()) {
            return List.of();
        }
        return List.of(wakeWord.trim());
    }
}
