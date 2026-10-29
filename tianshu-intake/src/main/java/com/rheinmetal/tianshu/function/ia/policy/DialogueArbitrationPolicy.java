package com.rheinmetal.tianshu.function.ia.policy;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueArbitrationDecision;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaim;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimMode;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Picks the owner of one turn from the hard claims the claim engine collected.
 *
 * <p>The rules are deliberately small and in this order: a hard claim wins by its source (a pointed-at target
 * outranks a named one), then participant priority, then the stable participant id. With no claim, the
 * participant that won the previous turn keeps the round while its lease is alive. With no lease, the default
 * owner takes the turn; with no default owner the turn is rejected instead of being guessed.</p>
 */
public final class DialogueArbitrationPolicy {
    public DialogueArbitrationDecision decide(List<DialogueParticipantDescriptor> participants, List<DialogueClaim> claims) {
        return decide(participants, claims, "");
    }

    /**
     * @param leaseHolderId the participant that still holds the turn from the previous round, or blank
     */
    public DialogueArbitrationDecision decide(
            List<DialogueParticipantDescriptor> participants,
            List<DialogueClaim> claims,
            String leaseHolderId
    ) {
        if (participants == null || participants.isEmpty()) {
            return DialogueArbitrationDecision.rejected("NO_PARTICIPANT");
        }
        Map<String, DialogueParticipantDescriptor> participantById = participants.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(DialogueParticipantDescriptor::participantId, Function.identity(), (left, right) -> left));
        Optional<DialogueClaim> bestClaim = (claims == null ? List.<DialogueClaim>of() : claims).stream()
                .filter(claim -> claim != null && participantById.containsKey(claim.participantId()))
                .max(claimComparator());
        if (bestClaim.isPresent()) {
            DialogueClaim claim = bestClaim.get();
            return DialogueArbitrationDecision.accepted(participantById.get(claim.participantId()), claim, "HARD_CLAIM");
        }
        if (leaseHolderId != null && !leaseHolderId.isBlank() && participantById.containsKey(leaseHolderId)) {
            return DialogueArbitrationDecision.accepted(participantById.get(leaseHolderId), null, "LEASE_CONTINUED");
        }
        return defaultOwnerParticipant(participants)
                .map(owner -> DialogueArbitrationDecision.accepted(owner, null, "DEFAULT_OWNER"))
                .orElseGet(() -> DialogueArbitrationDecision.rejected("NO_OWNER"));
    }

    private Comparator<DialogueClaim> claimComparator() {
        return Comparator.comparingInt((DialogueClaim claim) -> claim.source().ordinal())
                .thenComparingInt(DialogueClaim::priority)
                .thenComparing(DialogueClaim::participantId, Comparator.reverseOrder());
    }

    private Optional<DialogueParticipantDescriptor> defaultOwnerParticipant(List<DialogueParticipantDescriptor> participants) {
        return participants.stream()
                .filter(participant -> participant != null && participant.claimProfile().mode() == DialogueClaimMode.DEFAULT_OWNER)
                .max(Comparator.comparing(DialogueParticipantDescriptor::priority)
                        .thenComparing(DialogueParticipantDescriptor::participantId, Comparator.reverseOrder()));
    }
}