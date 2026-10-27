package com.rheinmetal.tianshu.function.ia.claim;

import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueEntityRef;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaim;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimMode;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimOperator;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimRule;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimSource;
import com.rheinmetal.tianshu.function.ia.model.DialogueArbitrationInput;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.function.ia.contract.IrModuleMatch;
import com.rheinmetal.tianshu.function.ia.contract.IrObjectMatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

public final class DialogueClaimEngine {
    public List<DialogueClaim> collectLocalClaims(List<DialogueParticipantDescriptor> participants, DialogueArbitrationInput input) {
        if (participants == null || participants.isEmpty()) {
            return List.of();
        }
        List<DialogueClaim> claims = new ArrayList<>();
        for (DialogueParticipantDescriptor participant : participants) {
            if (participant == null) {
                continue;
            }
            DialogueClaimProfile profile = participant.claimProfile();
            if (profile.mode() == DialogueClaimMode.DISABLED) {
                continue;
            }
            collectClaim(participant, profile, input).ifPresent(claims::add);
        }
        return List.copyOf(claims);
    }

    private java.util.Optional<DialogueClaim> collectClaim(DialogueParticipantDescriptor participant, DialogueClaimProfile profile, DialogueArbitrationInput input) {
        DialogueClaimSource source = null;
        StringJoiner reason = new StringJoiner(",");
        for (DialogueClaimRule rule : profile.rules()) {
            List<DialogueClaimCondition> matched = matchedConditions(rule, input);
            if (matched.isEmpty()) {
                continue;
            }
            if (!rule.ruleId().isBlank()) {
                reason.add(rule.ruleId());
            }
            DialogueClaimSource ruleSource = strongestSource(matched);
            if (source == null || ruleSource.ordinal() > source.ordinal()) {
                source = ruleSource;
            }
        }
        if (source == null) {
            return java.util.Optional.empty();
        }
        String reasonText = reason.length() == 0 ? "hard_claim" : "hard_claim:" + reason;
        return java.util.Optional.of(new DialogueClaim(participant.participantId(), source, participant.priority(), reasonText));
    }

    /**
     * The conditions a rule actually matched.
     *
     * <p>{@code ANY} keeps the subset that matched, because the claim's source depends on which of them did:
     * {@code wakeWord OR crosshairEntity} is an utterance claim when only the wake word matched. {@code ALL}
     * either matches every condition or none.</p>
     */
    private List<DialogueClaimCondition> matchedConditions(DialogueClaimRule rule, DialogueArbitrationInput input) {
        if (rule.conditions().isEmpty()) {
            return List.of();
        }
        if (rule.operator() == DialogueClaimOperator.ANY) {
            return rule.conditions().stream().filter(condition -> matchesCondition(condition, input)).toList();
        }
        return rule.conditions().stream().allMatch(condition -> matchesCondition(condition, input))
                ? rule.conditions()
                : List.of();
    }

    private static DialogueClaimSource strongestSource(List<DialogueClaimCondition> matched) {
        return matched.stream()
                .map(condition -> condition.type().source())
                .max(Comparator.comparingInt(Enum::ordinal))
                .orElse(DialogueClaimSource.UTTERANCE);
    }

    private boolean matchesCondition(DialogueClaimCondition condition, DialogueArbitrationInput input) {
        DialogueInteractionHints hints = input.interactionHints();
        DialogueContextSnapshot context = input.contextSnapshot();
        return switch (condition.type()) {
            case WAKE_WORD -> matchesAny(condition.values(), triggerWords(input));
            case HELD_ITEM -> matchesAny(condition.values(), List.of(hints.heldItemId()));
            case EQUIPPED_ITEM -> matchesAny(condition.values(), context.equippedItemIds());
            case MENTIONED_ENTITY -> matchesAny(condition.values(), mentionedEntityTypeIds(input));
            case CROSSHAIR_ENTITY -> matchesAny(condition.values(), crosshairEntityTypes(context));
            case INTERACTION_KEY -> hints.interactionKeyDown();
            case SNEAKING -> hints.sneaking();
        };
    }

    /**
     * The trigger words the repair step reported, each in the form the player said it.
     *
     * <p>A wake-word condition asks whether the word was said as a module trigger at all. The repair step reports one
     * entry per hit trigger word and no longer separates a module's names from its variants, so the condition is
     * compared against the spoken side of every module match; a word that no module registered still matches
     * nothing, which is what keeps an unrelated utterance from claiming the turn.</p>
     */
    private static List<String> triggerWords(DialogueArbitrationInput input) {
        return input.moduleMatches().stream()
                .filter(match -> IrModuleMatch.KIND_WAKE.equals(match.kind()))
                .map(IrModuleMatch::spokenWord)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    /**
     * Registry ids of the mentioned objects that are creatures.
     *
     * <p>Decided by the match's own registry kind ({@link IrObjectMatch#entity()}), not by the shape of the id:
     * mentioning {@code minecraft:minecart} as an item must not claim a condition that asks for the entity.</p>
     */
    private static List<String> mentionedEntityTypeIds(DialogueArbitrationInput input) {
        return input.objectMatches().stream()
                .filter(IrObjectMatch::entity)
                .map(IrObjectMatch::registryId)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private static List<String> crosshairEntityTypes(DialogueContextSnapshot context) {
        return context.entityRefs().stream()
                .filter(DialogueEntityRef::crosshairTarget)
                .map(DialogueEntityRef::entityTypeId)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private static boolean matchesAny(List<String> expected, List<String> actual) {
        if (actual == null || actual.isEmpty()) {
            return false;
        }
        if (expected == null || expected.isEmpty()) {
            return actual.stream().anyMatch(value -> value != null && !value.isBlank());
        }
        return actual.stream().anyMatch(value -> expected.stream().anyMatch(expectedValue -> matchesValue(expectedValue, value)));
    }

    private static boolean matchesValue(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null || actual.isBlank()) {
            return false;
        }
        String normalizedExpected = normalize(expected);
        String normalizedActual = normalize(actual);
        if (normalizedExpected.equals(normalizedActual)) {
            return true;
        }
        if (normalizedExpected.endsWith(":*")) {
            return normalizedActual.startsWith(normalizedExpected.substring(0, normalizedExpected.length() - 1));
        }
        return normalizedExpected.startsWith("#") && normalizedActual.equals(normalizedExpected.substring(1));
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
