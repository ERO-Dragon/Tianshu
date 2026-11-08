package com.rheinmetal.tianshu.function.lab;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimRule;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueHoldTier;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTriggerGroup;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The three bench participants: one per claim kind.
 *
 * <p>They exist to make the three ways a participant can ask for a turn observable side by side:</p>
 *
 * <ul>
 *   <li>{@code alpha} claims from the crosshair target — what the player is pointing at.</li>
 *   <li>{@code beta} claims from the held item — what the player is carrying.</li>
 *   <li>{@code gamma} claims from a wake word — what the player said.</li>
 * </ul>
 *
 * <p>The first two are {@code CONTEXT} claims and the third is {@code UTTERANCE}; the arbiter ranks the
 * source before any priority, so pointing at something outranks saying a name even when the named
 * participant has the higher priority. Priorities are still distinct so the winner inside the CONTEXT tier
 * is readable without knowing the stable-id tie-break.</p>
 */
public final class LabParticipants {
    public static final String ALPHA = "alpha";
    public static final String BETA = "beta";
    public static final String GAMMA = "gamma";

    /** Crosshair outranks held: both are context, and the bench makes that order visible. */
    public static final int ALPHA_PRIORITY = 30;
    public static final int BETA_PRIORITY = 20;
    public static final int GAMMA_PRIORITY = 10;

    /** The default wake words; the bench page can replace them. */
    public static final String DEFAULT_WAKE_WORDS = "伽马,gamma";

    private LabParticipants() {
    }

    /**
     * @param crosshairEntityIds entity type ids that make this participant claim; empty means any entity
     *                           the crosshair is on, which is what a quick "aim at an animal" test needs
     * @param holdTier           how long this participant keeps the turn after winning it; the bench can
     *                           switch this to observe the lease, including {@code NONE}
     */
    public static DialogueParticipantDescriptor alpha(String displayName, List<String> crosshairEntityIds,
                                                      DialogueHoldTier holdTier) {
        List<String> ids = crosshairEntityIds == null ? List.of() : crosshairEntityIds;
        return descriptor(
                ALPHA,
                displayName,
                ALPHA_PRIORITY,
                DialogueClaimProfile.rules(DialogueClaimRule.any(
                        "crosshair",
                        DialogueClaimCondition.crosshairEntity(ids.toArray(String[]::new)))),
                DialogueTriggerGroup.EMPTY,
                LabProtocolAdapter.ALPHA_CAPABILITY,
                "minecraft:textures/item/spyglass.png",
                holdTier
        );
    }

    public static DialogueParticipantDescriptor beta(String displayName, String heldItemId,
                                                     DialogueHoldTier holdTier) {
        String itemId = requireText(heldItemId, "heldItemId");
        return descriptor(
                BETA,
                displayName,
                BETA_PRIORITY,
                DialogueClaimProfile.rules(DialogueClaimRule.any(
                        "held",
                        DialogueClaimCondition.heldItem(itemId))),
                DialogueTriggerGroup.EMPTY,
                LabProtocolAdapter.BETA_CAPABILITY,
                "minecraft:textures/item/diamond.png",
                holdTier
        );
    }

    public static DialogueParticipantDescriptor gamma(String displayName, List<String> wakeWords,
                                                      DialogueHoldTier holdTier) {
        List<String> words = normalize(wakeWords);
        if (words.isEmpty()) {
            throw new IllegalArgumentException("gamma needs at least one wake word");
        }
        return descriptor(
                GAMMA,
                displayName,
                GAMMA_PRIORITY,
                DialogueClaimProfile.rules(DialogueClaimRule.any(
                        "wake",
                        DialogueClaimCondition.wakeWord(words.toArray(String[]::new)))),
                DialogueTriggerGroup.of(words, List.of()),
                LabProtocolAdapter.GAMMA_CAPABILITY,
                "minecraft:textures/item/bell.png",
                holdTier
        );
    }

    /** Splits a page field such as {@code "minecraft:cow, minecraft:pig"} into distinct ids. */
    public static List<String> parseList(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return List.copyOf(values);
    }

    private static DialogueParticipantDescriptor descriptor(
            String participantId,
            String displayName,
            int priority,
            DialogueClaimProfile claimProfile,
            DialogueTriggerGroup triggerGroup,
            String routeCapability,
            String icon,
            DialogueHoldTier holdTier) {
        return new DialogueParticipantDescriptor(
                participantId,
                LabProtocolAdapter.MODULE_ID,
                displayName,
                priority,
                claimProfile,
                triggerGroup,
                routeCapability,
                DialogueTurnProcessingPolicy.DEFAULT,
                icon,
                holdTier == null ? DialogueHoldTier.NONE : holdTier
        );
    }

    private static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>(values.size());
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        return List.copyOf(cleaned);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}