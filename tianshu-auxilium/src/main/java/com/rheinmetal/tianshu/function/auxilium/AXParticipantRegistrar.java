package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.bridge.AXDialogueChannel;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueAttentionDecay;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimRule;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTriggerGroup;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Announces this participant to the dialogue domain.
 *
 * <p>The descriptor is protocol vocabulary (api), so building it needs no bridge; only handing it over does,
 * which is why the channel is the one partner-shaped thing here. Without a dialogue domain the registration
 * availability reads false and nothing is announced — a module that cannot be addressed simply stays quiet.</p>
 */
public final class AXParticipantRegistrar {
    public static final String PARTICIPANT_ID = "tianshu.AX";

    private final AXDialogueChannel channel;
    private final AuxiliumAssistantSettings settings;
    private final AtomicBoolean registered = new AtomicBoolean(false);

    public AXParticipantRegistrar(AXDialogueChannel channel, AuxiliumAssistantSettings settings) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.settings = settings == null ? AuxiliumAssistantSettings.DEFAULT : settings;
    }

    public void register() {
        List<String> wakeWords = wakeWords();
        if (!channel.participantRegistrationAvailable()) {
            return;
        }
        if (!registered.compareAndSet(false, true)) {
            return;
        }
        channel.registerParticipant(new DialogueParticipantDescriptor(
                PARTICIPANT_ID,
                AXModule.MODULE_ID,
                settings.wakeWord(),
                0,
                claimProfile(wakeWords),
                triggerGroup(wakeWords),
                AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY,
                DialogueTurnProcessingPolicy.DEFAULT,
                "",
                settings.fallbackScore()
        ));
    }

    public void unregister() {
        if (!registered.compareAndSet(true, false)) {
            return;
        }
        channel.unregisterParticipant();
    }

    private DialogueClaimProfile claimProfile(List<String> wakeWords) {
        if (wakeWords.isEmpty()) {
            return DialogueClaimProfile.DEFAULT_OWNER;
        }
        return DialogueClaimProfile.defaultOwnerWithRules(DialogueClaimRule.anyStrong(
                "ax.wake_word",
                DialogueAttentionDecay.SLOW,
                wakeWords.stream().map(DialogueClaimCondition::wakeWord).toArray(DialogueClaimCondition[]::new)
        ));
    }

    private DialogueTriggerGroup triggerGroup(List<String> wakeWords) {
        return DialogueTriggerGroup.of(wakeWords, List.of());
    }

    private List<String> wakeWords() {
        String wakeWord = settings.wakeWord();
        if (wakeWord == null || wakeWord.isBlank()) {
            return List.of();
        }
        return List.of(wakeWord.trim());
    }
}
