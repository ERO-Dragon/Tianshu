package com.rheinmetal.tianshu.function.ia.policy;

import com.rheinmetal.tianshu.function.ia.model.DialogueArbitrationDecision;
import com.rheinmetal.tianshu.function.ia.model.DialogueAttentionState;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaim;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimMode;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DialogueArbitrationPolicy {
    public DialogueArbitrationDecision decide(List<DialogueParticipantDescriptor> participants, List<DialogueClaim> claims, Optional<DialogueAttentionState> attentionState) {
        if (participants == null || participants.isEmpty()) {
            return DialogueArbitrationDecision.rejected("NO_PARTICIPANT");
        }
        Map<String, DialogueParticipantDescriptor> participantById = participants.stream().collect(Collectors.toMap(DialogueParticipantDescriptor::participantId, Function.identity(), (left, right) -> left));
        List<DialogueClaim> validClaims = claims == null ? List.of() : claims.stream()
                .filter(claim -> participantById.containsKey(claim.participantId()))
                .toList();
        Optional<DialogueClaim> bestClaim = bestHardClaim(validClaims);
        if (bestClaim.isPresent()) {
            DialogueParticipantDescriptor owner = participantById.get(bestClaim.get().participantId());
            return DialogueArbitrationDecision.accepted(owner, bestClaim.get(), "HARD_CLAIM");
        }
        if (attentionState.isPresent()) {
            Optional<DialogueParticipantDescriptor> owner = participantById.values().stream()
                    .filter(participant -> attentionState.get().ownedBy(participant.moduleId(), participant.participantId()))
                    .findFirst();
            if (owner.isPresent()) {
                return DialogueArbitrationDecision.accepted(owner.get(), null, "ATTENTION_CONTINUED");
            }
        }
        return defaultOwnerParticipant(participants)
                .map(owner -> DialogueArbitrationDecision.accepted(owner, null, "DEFAULT_OWNER"))
                .orElseGet(() -> DialogueArbitrationDecision.rejected("NO_OWNER"));
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
}
