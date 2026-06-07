package com.rheinmetal.tianshu.function.ia.policy;

import com.rheinmetal.tianshu.function.ia.model.DialogueArbitrationDecision;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaim;
import com.rheinmetal.tianshu.function.ia.model.DialogueInterruptPolicy;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.function.ia.model.DialogueSession;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DialogueArbitrationPolicy {
    public DialogueArbitrationDecision decide(List<DialogueParticipantDescriptor> participants, List<DialogueClaim> claims, Optional<DialogueSession> activeSession, long nowMillis, boolean explicitPlayerAction) {
        if (participants == null || participants.isEmpty()) {
            return DialogueArbitrationDecision.rejected("NO_PARTICIPANT");
        }
        Map<String, DialogueParticipantDescriptor> participantById = participants.stream().collect(Collectors.toMap(DialogueParticipantDescriptor::participantId, Function.identity(), (left, right) -> left));
        Optional<DialogueClaim> bestClaim = claims.stream()
                .filter(claim -> participantById.containsKey(claim.participantId()))
                .max(claimComparator());
        if (bestClaim.isEmpty()) {
            return DialogueArbitrationDecision.rejected("NO_CLAIM");
        }
        DialogueParticipantDescriptor owner = participantById.get(bestClaim.get().participantId());
        if (activeSession.isPresent()) {
            DialogueSession session = activeSession.get();
            boolean sameOwner = session.ownedBy(owner.moduleId(), owner.participantId());
            if (sameOwner) {
                return DialogueArbitrationDecision.accepted(owner, bestClaim.get(), "OWNER_CONTINUED", false);
            }
            if (!canPreempt(owner, session, nowMillis, explicitPlayerAction)) {
                return DialogueArbitrationDecision.rejected("ACTIVE_OWNER_PROTECTED");
            }
            return DialogueArbitrationDecision.accepted(owner, bestClaim.get(), "OWNER_PREEMPTED", true);
        }
        return DialogueArbitrationDecision.accepted(owner, bestClaim.get(), "OWNER_CLAIMED", false);
    }

    private boolean canPreempt(DialogueParticipantDescriptor candidate, DialogueSession session, long nowMillis, boolean explicitPlayerAction) {
        DialogueInterruptPolicy policy = candidate.interruptPolicy();
        if (policy == DialogueInterruptPolicy.DENY) {
            return false;
        }
        if (policy == DialogueInterruptPolicy.ALWAYS_ALLOW) {
            return true;
        }
        if (policy == DialogueInterruptPolicy.ALLOW_AFTER_LEASE) {
            return session.leaseExpireAtMillis() <= nowMillis;
        }
        if (policy == DialogueInterruptPolicy.ALLOW_ON_PLAYER_ACTION) {
            return explicitPlayerAction;
        }
        return candidate.priority() > 0 && session.leaseExpireAtMillis() <= nowMillis;
    }

    private Comparator<DialogueClaim> claimComparator() {
        return Comparator.comparing(DialogueClaim::exclusive)
                .thenComparing(DialogueClaim::score)
                .thenComparing(DialogueClaim::confidence)
                .thenComparing(DialogueClaim::priority)
                .thenComparing(DialogueClaim::participantId, Comparator.reverseOrder());
    }
}
