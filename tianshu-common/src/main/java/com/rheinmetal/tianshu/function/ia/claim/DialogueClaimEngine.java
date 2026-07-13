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
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueArbitrationInput;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        List<DialogueClaimRule> matchedRules = new ArrayList<>();
        StringJoiner reason = new StringJoiner(",");
        for (DialogueClaimRule rule : profile.rules()) {
            if (!matchesRule(rule, input)) {
                continue;
            }
            matchedRules.add(rule);
            if (!rule.ruleId().isBlank()) {
                reason.add(rule.ruleId());
            }
        }
        if (matchedRules.isEmpty()) {
            return java.util.Optional.empty();
        }
        DialogueClaimRule bestRule = matchedRules.stream().max(ruleComparator()).orElseThrow();
        String reasonText = reason.length() == 0 ? "hard_claim" : "hard_claim:" + reason;
        return java.util.Optional.of(new DialogueClaim(participant.participantId(), bestRule.strength(), bestRule.decay(), participant.priority(), reasonText));
    }

    private boolean matchesRule(DialogueClaimRule rule, DialogueArbitrationInput input) {
        if (rule.conditions().isEmpty()) {
            return false;
        }
        if (rule.operator() == DialogueClaimOperator.ANY) {
            return rule.conditions().stream().anyMatch(condition -> matchesCondition(condition, input));
        }
        return rule.conditions().stream().allMatch(condition -> matchesCondition(condition, input));
    }

    private boolean matchesCondition(DialogueClaimCondition condition, DialogueArbitrationInput input) {
        DialogueInteractionHints hints = input.interactionHints();
        DialogueContextSnapshot context = input.contextSnapshot();
        return switch (condition.type()) {
            case WAKE_WORD -> matchesAny(condition.values(), input.matchedWakeWords());
            case HELD_ITEM -> matchesAny(condition.values(), List.of(hints.heldItemId()));
            case EQUIPPED_ITEM -> matchesAny(condition.values(), context.equippedItemIds());
            case MENTIONED_ENTITY -> matchesAny(condition.values(), input.matchedEntityTypeIds());
            case CROSSHAIR_ENTITY -> matchesAny(condition.values(), crosshairEntityTypes(context));
            case NEAREST_ENTITY_WITHIN -> matchesNearestEntityWithin(condition, context);
            case CROSSHAIR_HIT -> hints.crosshairHit();
            case INTERACTION_KEY -> hints.interactionKeyDown();
            case SNEAKING -> hints.sneaking();
            case INTERACTION_TAG -> matchesAny(condition.values(), hints.tags());
            case CONTEXT_FACT -> matchesContextFact(condition, context.facts());
        };
    }

    private static List<String> crosshairEntityTypes(DialogueContextSnapshot context) {
        return context.entityRefs().stream()
                .filter(DialogueEntityRef::crosshairTarget)
                .map(DialogueEntityRef::entityTypeId)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private static boolean matchesNearestEntityWithin(DialogueClaimCondition condition, DialogueContextSnapshot context) {
        if (condition.maxDistance() <= 0.0D || condition.values().isEmpty()) {
            return false;
        }
        return context.entityRefs().stream()
                .filter(ref -> ref.distance() <= condition.maxDistance())
                .anyMatch(ref -> matchesAny(condition.values(), List.of(ref.entityTypeId())));
    }

    private static boolean matchesContextFact(DialogueClaimCondition condition, Map<String, String> facts) {
        if (condition.factKey().isBlank() || facts == null || facts.isEmpty()) {
            return false;
        }
        String value = facts.get(condition.factKey());
        if (value == null || value.isBlank()) {
            return false;
        }
        return condition.values().isEmpty() || matchesAny(condition.values(), List.of(value));
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

    private static Comparator<DialogueClaimRule> ruleComparator() {
        return Comparator
                .comparing(DialogueClaimRule::strength)
                .thenComparing(rule -> rule.decay() == null ? 0.0D : -rule.decay().perSecond())
                .thenComparing(DialogueClaimRule::ruleId, Comparator.reverseOrder());
    }
}
