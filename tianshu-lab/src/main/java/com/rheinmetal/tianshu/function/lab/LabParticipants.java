package com.rheinmetal.tianshu.function.lab;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimRule;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTriggerGroup;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;

import java.util.List;

/**
 * The two fake participants and the words that make them claim a turn.
 *
 * <p>The words are test data, not UI copy: a wake word is what the player says, and every language
 * variant listed here is one the bench claims on. Adding a language means adding a word, the same way a
 * real module declares its own triggers.</p>
 */
public final class LabParticipants {
    public static final String ALPHA = "alpha";
    public static final String BETA = "beta";

    /** The words that make each participant claim; the first one is also how a tester calls it. */
    private static final List<String> ALPHA_WORDS = List.of("阿尔法", "alpha");
    private static final List<String> BETA_WORDS = List.of("贝塔", "beta");

    private LabParticipants() {
    }

    public static DialogueParticipantDescriptor alpha(String displayName) {
        return descriptor(ALPHA, displayName, 30, ALPHA_WORDS, LabProtocolAdapter.ALPHA_CAPABILITY,
                "minecraft:textures/item/diamond.png");
    }

    public static DialogueParticipantDescriptor beta(String displayName) {
        return descriptor(BETA, displayName, 10, BETA_WORDS, LabProtocolAdapter.BETA_CAPABILITY,
                "minecraft:textures/item/emerald.png");
    }

    public static List<String> alphaWords() {
        return ALPHA_WORDS;
    }

    public static List<String> betaWords() {
        return BETA_WORDS;
    }

    private static DialogueParticipantDescriptor descriptor(String participantId, String displayName,
                                                            int priority, List<String> wakeWords,
                                                            String routeCapability, String icon) {
        return new DialogueParticipantDescriptor(
                participantId,
                LabProtocolAdapter.MODULE_ID,
                displayName,
                priority,
                DialogueClaimProfile.rules(DialogueClaimRule.any(
                        "wake",
                        DialogueClaimCondition.wakeWord(wakeWords.toArray(String[]::new)))),
                DialogueTriggerGroup.of(wakeWords, List.of()),
                routeCapability,
                DialogueTurnProcessingPolicy.DEFAULT,
                // A rule-based participant must declare a partner icon; the bench borrows two vanilla item
                // textures so the marker draws without shipping an asset pack for a dev-only mod.
                icon,
                com.rheinmetal.tianshu.protocol.dialogue.model.DialogueHoldTier.NONE
        );
    }
}