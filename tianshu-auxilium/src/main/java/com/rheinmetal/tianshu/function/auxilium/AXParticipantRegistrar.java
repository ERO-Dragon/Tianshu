package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.bridge.AXDialogueChannel;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueHoldTier;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimRule;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTriggerGroup;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Announces this participant to the dialogue domain.
 *
 * <p>The descriptor is protocol vocabulary (api), so building it needs no bridge; only handing it over does,
 * which is why the channel is the one partner-shaped thing here. Without a dialogue domain the registration
 * availability reads false and nothing is announced — a module that cannot be addressed simply stays quiet.</p>
 */
public final class AXParticipantRegistrar {
    public static final String PARTICIPANT_ID = "tianshu.AX";
    /** The name IA shows in UI and diagnostics: a language key, never a literal. */
    public static final String DISPLAY_NAME_KEY = "tianshu.ax.participant.display_name";

    private final AXDialogueChannel channel;
    private final AuxiliumAssistantSettings settings;
    private final Supplier<String> displayName;
    private final AtomicBoolean registered = new AtomicBoolean(false);

    public AXParticipantRegistrar(AXDialogueChannel channel, AuxiliumAssistantSettings settings) {
        this(channel, settings, () -> "");
    }

    /**
     * @param displayName resolves the participant's display name from the language table; a blank result
     *                    falls back to {@link #PARTICIPANT_ID} so IA never shows a nameless participant
     */
    public AXParticipantRegistrar(AXDialogueChannel channel, AuxiliumAssistantSettings settings,
                                  Supplier<String> displayName) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.settings = settings == null ? AuxiliumAssistantSettings.DEFAULT : settings;
        this.displayName = displayName == null ? () -> "" : displayName;
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
                resolvedDisplayName(),
                0,
                claimProfile(wakeWords),
                triggerGroup(wakeWords),
                AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY,
                DialogueTurnProcessingPolicy.DEFAULT,
                "",
                DialogueHoldTier.TEN_SECONDS
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
        return DialogueClaimProfile.defaultOwnerWithRules(DialogueClaimRule.any(
                "ax.wake_word",
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

    private String resolvedDisplayName() {
        String name = displayName.get();
        return name == null || name.isBlank() ? PARTICIPANT_ID : name.trim();
    }
}
