package com.rheinmetal.tianshu.function.ia.claim;

import com.rheinmetal.tianshu.function.ia.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.function.ia.context.DialogueEntityRef;
import com.rheinmetal.tianshu.function.ia.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaim;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimConditionType;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimMode;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimOperator;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimRule;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.function.ia.payload.DialogueArbitrationRequestPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

public final class DialogueClaimEngine {
    public List<DialogueClaim> collectLocalClaims(List<DialogueParticipantDescriptor> participants, DialogueArbitrationRequestPayload request) {
        if (participants == null || participants.isEmpty()) {
            return List.of();
        }
        List<DialogueClaim> claims = new ArrayList<>();
        List<DialogueParticipantDescriptor> fallbackParticipants = new ArrayList<>();
        for (DialogueParticipantDescriptor participant : participants) {
            if (participant == null) {
                continue;
            }
            DialogueClaimProfile profile = participant.claimProfile();
            if (profile.mode() == DialogueClaimMode.FALLBACK_ONLY) {
                fallbackParticipants.add(participant);
                continue;
            }
            if (profile.mode() == DialogueClaimMode.DISABLED) {
                continue;
            }
            DialogueClaim claim = score(participant, profile, request);
            if (claim.score() > 0.0D || claim.confidence() > 0.0D) {
                claims.add(claim);
            }
        }
        if (!claims.isEmpty()) {
            return List.copyOf(claims);
        }
        return fallbackParticipants.stream()
                .map(this::fallbackClaim)
                .toList();
    }

    private DialogueClaim score(DialogueParticipantDescriptor participant, DialogueClaimProfile profile, DialogueArbitrationRequestPayload request) {
        double score = 0.0D;
        double confidence = 0.0D;
        boolean exclusive = false;
        StringJoiner reason = new StringJoiner(",");
        for (DialogueClaimRule rule : profile.rules()) {
            if (!matchesRule(rule, request)) {
                continue;
            }
            score += rule.score();
            confidence += rule.confidence();
            exclusive = exclusive || rule.exclusive();
            if (!rule.ruleId().isBlank()) {
                reason.add(rule.ruleId());
            }
        }
        if (score > 0.0D && request.interactionHints().crosshairHit()) {
            score += 0.05D;
        }
        if (score > 0.0D && request.interactionHints().interactionKeyDown()) {
            score += 0.05D;
        }
        String reasonText = reason.length() == 0 ? "rule_score" : "rule_score:" + reason;
        return new DialogueClaim(participant.participantId(), score, confidence, participant.priority(), exclusive, reasonText);
    }

    private DialogueClaim fallbackClaim(DialogueParticipantDescriptor participant) {
        return new DialogueClaim(participant.participantId(), 0.1D, 0.1D, participant.priority(), false, "fallback");
    }

    private boolean matchesRule(DialogueClaimRule rule, DialogueArbitrationRequestPayload request) {
        if (rule.conditions().isEmpty()) {
            return false;
        }
        if (rule.operator() == DialogueClaimOperator.ANY) {
            return rule.conditions().stream().anyMatch(condition -> matchesCondition(condition, request));
        }
        return rule.conditions().stream().allMatch(condition -> matchesCondition(condition, request));
    }

    private boolean matchesCondition(DialogueClaimCondition condition, DialogueArbitrationRequestPayload request) {
        DialogueInteractionHints hints = request.interactionHints();
        DialogueContextSnapshot context = request.contextSnapshot();
        return switch (condition.type()) {
            case HOTWORD -> matchesAny(condition.values(), request.matchedHotwords());
            case MATCHED_ITEM -> matchesAny(condition.values(), request.matchedItemIds());
            case HELD_ITEM -> matchesAny(condition.values(), List.of(hints.heldItemId()));
            case MATCHED_ENTITY -> matchesAny(condition.values(), matchedEntityTypes(request));
            case CROSSHAIR_ENTITY -> matchesAny(condition.values(), crosshairEntityTypes(context));
            case CROSSHAIR_HIT -> hints.crosshairHit();
            case INTERACTION_KEY -> hints.interactionKeyDown();
            case SNEAKING -> hints.sneaking();
            case INTERACTION_TAG -> matchesAny(condition.values(), hints.tags());
            case CONTEXT_ITEM -> matchesAny(condition.values(), context.itemIds());
            case CONTEXT_FACT -> matchesContextFact(condition, context.facts());
        };
    }

    private static List<String> matchedEntityTypes(DialogueArbitrationRequestPayload request) {
        List<String> values = new ArrayList<>(request.matchedEntityRefs());
        request.contextSnapshot().entityRefs().stream()
                .map(DialogueEntityRef::entityTypeId)
                .filter(value -> value != null && !value.isBlank())
                .forEach(values::add);
        return values;
    }

    private static List<String> crosshairEntityTypes(DialogueContextSnapshot context) {
        return context.entityRefs().stream()
                .filter(DialogueEntityRef::crosshairTarget)
                .map(DialogueEntityRef::entityTypeId)
                .filter(value -> value != null && !value.isBlank())
                .toList();
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
}
