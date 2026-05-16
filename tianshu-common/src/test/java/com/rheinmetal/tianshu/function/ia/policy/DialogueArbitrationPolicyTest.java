package com.rheinmetal.tianshu.function.ia.policy;

import com.rheinmetal.tianshu.function.ia.model.DialogueClaim;
import com.rheinmetal.tianshu.function.ia.model.DialogueInterruptPolicy;
import com.rheinmetal.tianshu.function.ia.model.DialogueLeasePolicy;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueArbitrationPolicyTest {
    @Test
    void selectsHighestPriorityThenScoreClaim() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();
        DialogueParticipantDescriptor low = descriptor("low", 1);
        DialogueParticipantDescriptor high = descriptor("high", 2);

        var decision = policy.decide(
                List.of(low, high),
                List.of(new DialogueClaim("low", 1.0D, 1.0D, 1, false, ""), new DialogueClaim("high", 0.2D, 0.2D, 2, false, "")),
                Optional.empty(),
                100L,
                false
        );

        assertTrue(decision.accepted());
        assertEquals("high", decision.owner().participantId());
    }

    @Test
    void rejectsWhenNoClaimExists() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();

        var decision = policy.decide(List.of(descriptor("p", 1)), List.of(), Optional.empty(), 100L, false);

        assertTrue(!decision.accepted());
        assertEquals("NO_CLAIM", decision.reason());
    }

    private DialogueParticipantDescriptor descriptor(String participantId, int priority) {
        return new DialogueParticipantDescriptor(participantId, "module." + participantId, participantId, priority, List.of(), List.of(), List.of(), "ROUTE", DialogueInterruptPolicy.ALLOW_AFTER_LEASE, DialogueLeasePolicy.DEFAULT);
    }
}
