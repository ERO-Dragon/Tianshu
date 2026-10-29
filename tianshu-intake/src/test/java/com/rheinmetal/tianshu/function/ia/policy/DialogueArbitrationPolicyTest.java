package com.rheinmetal.tianshu.function.ia.policy;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaim;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimSource;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTriggerGroup;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueArbitrationPolicyTest {
    private static final Optional<DialogueParticipantDescriptor> NOBODY = Optional.empty();

    @Test
    void hardClaimBeatsTheDefaultOwner() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();

        var decision = policy.decide(
                List.of(descriptor("claimed", 1), defaultOwner("default", 0)),
                List.of(claim("claimed", DialogueClaimSource.UTTERANCE, 1)),
                Optional.of(defaultOwner("default", 0))
        );

        assertTrue(decision.accepted());
        assertEquals("claimed", decision.owner().participantId());
        assertEquals("HARD_CLAIM", decision.reason());
    }

    @Test
    void beingPointedAtOutranksBeingNamed() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();

        var decision = policy.decide(
                List.of(descriptor("named", 99), descriptor("aimed", 1), defaultOwner("default", 0)),
                List.of(
                        claim("named", DialogueClaimSource.UTTERANCE, 99),
                        claim("aimed", DialogueClaimSource.CONTEXT, 1)
                ),
                Optional.of(defaultOwner("default", 0))
        );

        assertTrue(decision.accepted());
        assertEquals("aimed", decision.owner().participantId());
        assertEquals("HARD_CLAIM", decision.reason());
    }

    @Test
    void priorityBreaksTiesWithinTheSameSource() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();

        var decision = policy.decide(
                List.of(descriptor("low", 1), descriptor("high", 2), defaultOwner("default", 0)),
                List.of(
                        claim("low", DialogueClaimSource.CONTEXT, 1),
                        claim("high", DialogueClaimSource.CONTEXT, 2)
                ),
                Optional.of(defaultOwner("default", 0))
        );

        assertTrue(decision.accepted());
        assertEquals("high", decision.owner().participantId());
    }

    @Test
    void stableParticipantIdBreaksRemainingTies() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();

        var decision = policy.decide(
                List.of(descriptor("alpha", 1), descriptor("beta", 1), defaultOwner("default", 0)),
                List.of(
                        claim("alpha", DialogueClaimSource.CONTEXT, 1),
                        claim("beta", DialogueClaimSource.CONTEXT, 1)
                ),
                Optional.of(defaultOwner("default", 0))
        );

        assertTrue(decision.accepted());
        assertEquals("alpha", decision.owner().participantId());
    }

    @Test
    void theDefaultOwnerHandedInTakesAnUnclaimedTurn() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();

        var decision = policy.decide(
                List.of(defaultOwner("first", 9), defaultOwner("second", 1)),
                List.of(),
                Optional.of(defaultOwner("second", 1))
        );

        assertTrue(decision.accepted());
        assertEquals("second", decision.owner().participantId(), "the policy uses the owner it was handed, it does not re-pick by priority");
        assertEquals("DEFAULT_OWNER", decision.reason());
    }

    @Test
    void rejectsWhenNothingClaimsAndThereIsNoDefaultOwner() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();

        var decision = policy.decide(List.of(descriptor("regular", 1)), List.of(), NOBODY);

        assertFalse(decision.accepted());
        assertEquals("NO_OWNER", decision.reason());
    }

    @Test
    void rejectsWhenNoParticipantIsRegistered() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();

        var decision = policy.decide(List.of(), List.of(claim("missing", DialogueClaimSource.CONTEXT, 1)), NOBODY);

        assertFalse(decision.accepted());
        assertEquals("NO_PARTICIPANT", decision.reason());
    }

    @Test
    void ignoresClaimsFromUnregisteredParticipants() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();

        var decision = policy.decide(
                List.of(defaultOwner("default", 0)),
                List.of(claim("missing", DialogueClaimSource.CONTEXT, 999)),
                Optional.of(defaultOwner("default", 0))
        );

        assertTrue(decision.accepted());
        assertEquals("default", decision.owner().participantId());
        assertEquals("DEFAULT_OWNER", decision.reason());
    }

    @Test
    void aDefaultOwnerThatIsNotRegisteredDoesNotTakeTheTurn() {
        DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();

        var decision = policy.decide(
                List.of(descriptor("regular", 1)),
                List.of(),
                Optional.of(defaultOwner("gone", 0))
        );

        assertFalse(decision.accepted());
        assertEquals("NO_OWNER", decision.reason());
    }

    private DialogueClaim claim(String participantId, DialogueClaimSource source, int priority) {
        return new DialogueClaim(participantId, source, priority, "");
    }

    private DialogueParticipantDescriptor descriptor(String participantId, int priority) {
        return new DialogueParticipantDescriptor(participantId, "module." + participantId, participantId, priority, DialogueClaimProfile.DISABLED, DialogueTriggerGroup.EMPTY, "ROUTE", DialogueTurnProcessingPolicy.DEFAULT);
    }

    private DialogueParticipantDescriptor defaultOwner(String participantId, int priority) {
        return new DialogueParticipantDescriptor(
                participantId,
                "module." + participantId,
                participantId,
                priority,
                DialogueClaimProfile.DEFAULT_OWNER,
                DialogueTriggerGroup.EMPTY,
                "ROUTE",
                DialogueTurnProcessingPolicy.DEFAULT
        );
    }
}