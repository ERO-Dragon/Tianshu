package com.rheinmetal.tianshu.function.ia.policy;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueArbitrationDecision;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueAttentionState;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaim;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimMode;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantScore;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collections;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DialogueArbitrationPolicy {
    public DialogueArbitrationDecision decide(List<DialogueParticipantDescriptor> participants, List<DialogueClaim> claims, Optional<DialogueAttentionState> attentionState) {
        return decide(participants, claims, Map.of(), attentionState, System.currentTimeMillis());
    }

    public DialogueArbitrationDecision decide(
            List<DialogueParticipantDescriptor> participants,
            List<DialogueClaim> claims,
            Map<String, DialogueParticipantScore> dynamicScores,
            Optional<DialogueAttentionState> attentionState,
            long nowMillis
    ) {
        if (participants == null || participants.isEmpty()) {
            return DialogueArbitrationDecision.rejected("NO_PARTICIPANT");
        }
        Map<String, DialogueParticipantDescriptor> participantById = participants.stream()
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toMap(DialogueParticipantDescriptor::participantId, Function.identity(), (left, right) -> left));
        List<DialogueClaim> validClaims = claims == null ? List.of() : claims.stream()
                .filter(claim -> participantById.containsKey(claim.participantId()))
                .toList();
        Optional<DialogueClaim> bestClaim = bestHardClaim(validClaims);
        if (bestClaim.isPresent()) {
            DialogueParticipantDescriptor owner = participantById.get(bestClaim.get().participantId());
            return DialogueArbitrationDecision.accepted(owner, bestClaim.get(), "HARD_CLAIM");
        }
        Map<String, DialogueParticipantScore> scores = effectiveScores(participants, dynamicScores, attentionState, nowMillis);
        Optional<DialogueParticipantDescriptor> defaultOwner = defaultOwnerParticipant(participants);
        if (scores.isEmpty()) {
            return DialogueArbitrationDecision.rejected("NO_OWNER");
        }
        ScoredOwner best = scores.values().stream()
                .map(score -> new ScoredOwner(participantById.get(score.participantId()), score))
                .filter(candidate -> candidate.participant() != null)
                .max(scoredComparator())
                .orElse(null);
        if (best == null || (defaultOwner.isEmpty()
                && best.score().score() <= defaultOwner.map(DialogueParticipantDescriptor::fallbackScore).orElse(0.0D)
                && best.score().source() != DialogueParticipantScore.Source.DYNAMIC)) {
            return DialogueArbitrationDecision.rejected("NO_OWNER");
        }
        String reason = best.score().source() == DialogueParticipantScore.Source.ATTENTION
                ? "ATTENTION_CONTINUED"
                : best.participant().claimProfile().mode() == DialogueClaimMode.DEFAULT_OWNER
                ? "DEFAULT_OWNER"
                : "DYNAMIC_SCORE";
        return DialogueArbitrationDecision.accepted(best.participant(), null, reason);
    }

    Map<String, DialogueParticipantScore> effectiveScores(
            List<DialogueParticipantDescriptor> participants,
            Map<String, DialogueParticipantScore> dynamicScores,
            Optional<DialogueAttentionState> attentionState,
            long nowMillis
    ) {
        if (participants == null || participants.isEmpty()) {
            return Map.of();
        }
        Map<String, DialogueParticipantScore> suppliedScores = dynamicScores == null ? Map.of() : dynamicScores;
        Map<String, DialogueParticipantScore> effective = new LinkedHashMap<>();
        for (DialogueParticipantDescriptor participant : participants) {
            if (participant == null) {
                continue;
            }
            DialogueParticipantScore supplied = suppliedScores.get(participant.participantId());
            double score = supplied == null ? participant.fallbackScore() : supplied.score();
            DialogueParticipantScore.Source source = supplied == null
                    ? DialogueParticipantScore.Source.FALLBACK
                    : supplied.source();
            if (participant.claimProfile().mode() == DialogueClaimMode.DEFAULT_OWNER) {
                score = Math.max(score, participant.fallbackScore());
            }
            if (attentionState != null && attentionState.isPresent()
                    && attentionState.get().ownedBy(participant.moduleId(), participant.participantId())) {
                double attention = attentionState.get().attentionAt(nowMillis);
                if (attention > score) {
                    score = attention;
                    source = DialogueParticipantScore.Source.ATTENTION;
                }
            }
            effective.put(participant.participantId(), new DialogueParticipantScore(
                    participant.participantId(), score, source, supplied == null ? "fallback" : supplied.reason()
            ));
        }
        return Collections.unmodifiableMap(effective);
    }

    private Optional<DialogueClaim> bestHardClaim(List<DialogueClaim> claims) {
        return claims == null || claims.isEmpty() ? Optional.empty() : claims.stream().max(claimComparator());
    }

    private Comparator<DialogueClaim> claimComparator() {
        return Comparator.comparing(DialogueClaim::strength)
                .thenComparing(DialogueClaim::priority)
                .thenComparing(DialogueClaim::participantId, Comparator.reverseOrder());
    }

    private Optional<DialogueParticipantDescriptor> defaultOwnerParticipant(List<DialogueParticipantDescriptor> participants) {
        return participants.stream()
                .filter(participant -> participant != null && participant.claimProfile().mode() == DialogueClaimMode.DEFAULT_OWNER)
                .max(Comparator.comparing(DialogueParticipantDescriptor::priority)
                        .thenComparing(DialogueParticipantDescriptor::participantId, Comparator.reverseOrder()));
    }

    private Comparator<ScoredOwner> scoredComparator() {
        return Comparator.comparingDouble((ScoredOwner owner) -> owner.score().score())
                .thenComparing(owner -> owner.participant().claimProfile().mode() == DialogueClaimMode.DEFAULT_OWNER ? 1 : 0)
                .thenComparingInt(owner -> owner.participant().priority())
                .thenComparing(owner -> owner.participant().participantId(), Comparator.reverseOrder());
    }

    private record ScoredOwner(DialogueParticipantDescriptor participant, DialogueParticipantScore score) {
    }
}
