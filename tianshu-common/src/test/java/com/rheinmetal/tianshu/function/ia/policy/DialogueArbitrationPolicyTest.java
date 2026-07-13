package com.rheinmetal.tianshu.function.ia.policy;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueAttentionDecay;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueAttentionState;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaim;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimStrength;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueArbitrationPolicyTest {
    @Test
    void strongHardClaimBeatsNormalHardClaim() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();
        DialogueParticipantDescriptor normal = descriptor("normal", 10);
        DialogueParticipantDescriptor strong = descriptor("strong", 1);

        var decision = policy.decide(
                List.of(normal, strong, defaultOwner()),
                List.of(
                        claim("normal", DialogueClaimStrength.NORMAL, 10),
                        claim("strong", DialogueClaimStrength.STRONG, 1)
                ),
                Optional.empty()
        );

        assertTrue(decision.accepted());
        assertEquals("strong", decision.owner().participantId());
        assertEquals("HARD_CLAIM", decision.reason());
    }

    @Test
    void priorityBreaksTiesWithinSameHardClaimStrength() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();
        DialogueParticipantDescriptor low = descriptor("low", 1);
        DialogueParticipantDescriptor high = descriptor("high", 2);

        var decision = policy.decide(
                List.of(low, high, defaultOwner()),
                List.of(
                        claim("low", DialogueClaimStrength.NORMAL, 1),
                        claim("high", DialogueClaimStrength.NORMAL, 2)
                ),
                Optional.empty()
        );

        assertTrue(decision.accepted());
        assertEquals("high", decision.owner().participantId());
    }

    @Test
    void currentHardClaimBeatsHistoricalAttention() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();
        DialogueParticipantDescriptor oldOwner = descriptor("old", 1);
        DialogueParticipantDescriptor newOwner = descriptor("new", 1);
        DialogueAttentionState attention = new DialogueAttentionState("player", "module.old", "old", 1.0D, DialogueAttentionDecay.SLOW, 100L);

        var decision = policy.decide(
                List.of(oldOwner, newOwner, defaultOwner()),
                List.of(claim("new", DialogueClaimStrength.NORMAL, 1)),
                Optional.of(attention)
        );

        assertTrue(decision.accepted());
        assertEquals("new", decision.owner().participantId());
        assertEquals("HARD_CLAIM", decision.reason());
    }

    @Test
    void historicalAttentionContinuesOnlyWhenNoHardClaimExists() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();
        DialogueParticipantDescriptor owner = descriptor("owner", 1);
        DialogueAttentionState attention = new DialogueAttentionState("player", "module.owner", "owner", 0.7D, DialogueAttentionDecay.SLOW, 100L);

        var decision = policy.decide(List.of(owner, defaultOwner()), List.of(), Optional.of(attention));

        assertTrue(decision.accepted());
        assertEquals("owner", decision.owner().participantId());
        assertEquals("ATTENTION_CONTINUED", decision.reason());
    }

    @Test
    void defaultOwnerHandlesWhenNoHardClaimOrAttentionExists() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();

        var decision = policy.decide(List.of(descriptor("regular", 1), defaultOwner()), List.of(), Optional.empty());

        assertTrue(decision.accepted());
        assertEquals("default", decision.owner().participantId());
        assertEquals("DEFAULT_OWNER", decision.reason());
    }

    @Test
    void rejectsWhenNoParticipantCanClaimOrDefaultOwner() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();

        var decision = policy.decide(List.of(descriptor("regular", 1)), List.of(), Optional.empty());

        assertTrue(!decision.accepted());
        assertEquals("NO_OWNER", decision.reason());
    }

    @Test
    void ignoresClaimsFromUnregisteredParticipants() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();

        var decision = policy.decide(
                List.of(defaultOwner()),
                List.of(claim("missing", DialogueClaimStrength.STRONG, 999)),
                Optional.empty()
        );

        assertTrue(decision.accepted());
        assertEquals("default", decision.owner().participantId());
        assertEquals("DEFAULT_OWNER", decision.reason());
    }

    @Test
    void stableParticipantIdTieBreakChoosesLexicographicallyEarlierId() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();

        var decision = policy.decide(
                List.of(descriptor("alpha", 1), descriptor("beta", 1), defaultOwner()),
                List.of(
                        claim("alpha", DialogueClaimStrength.NORMAL, 1),
                        claim("beta", DialogueClaimStrength.NORMAL, 1)
                ),
                Optional.empty()
        );

        assertTrue(decision.accepted());
        assertEquals("alpha", decision.owner().participantId());
    }

    private DialogueClaim claim(String participantId, DialogueClaimStrength strength, int priority) {
        return new DialogueClaim(participantId, strength, DialogueAttentionDecay.FAST, priority, "");
    }

    private DialogueParticipantDescriptor descriptor(String participantId, int priority) {
        return new DialogueParticipantDescriptor(participantId, "module." + participantId, participantId, priority, List.of(), List.of(), List.of(), DialogueClaimProfile.DISABLED, "ROUTE", DialogueTurnProcessingPolicy.DEFAULT);
    }

    private DialogueParticipantDescriptor defaultOwner() {
        return new DialogueParticipantDescriptor("default", "module.default", "default", 0, List.of(), List.of(), List.of(), DialogueClaimProfile.DEFAULT_OWNER, "ROUTE", DialogueTurnProcessingPolicy.DEFAULT);
    }
}
