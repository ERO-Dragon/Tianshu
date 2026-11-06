package com.rheinmetal.tianshu.function.ia.policy;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueArbitrationDecision;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaim;
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
 * participant that won the previous turn keeps the round while its lease is alive. With no lease, the turn goes
 * to the default owner handed in here — the policy does not decide who that is, because that answer depends on
 * the player's settings; with no default owner the turn is rejected instead of being guessed.</p>
 *
 * <p>Every lookup uses the module-qualified participant key. A participant id is module-internal, so the same
 * id in two modules must never make one module's claim resolve to the other module's descriptor.</p>
 */
public final class DialogueArbitrationPolicy {
    /** The winning reason a hard claim produced; also the value carried by {@code DialogueArbitrationDecision.reason()}. */
    public static final String REASON_HARD_CLAIM = "HARD_CLAIM";
    /** The winning reason a still-live lease produced. */
    public static final String REASON_LEASE_CONTINUED = "LEASE_CONTINUED";
    /** The winning reason the configured out-of-box/picked default owner produced. */
    public static final String REASON_DEFAULT_OWNER = "DEFAULT_OWNER";
    /** No participant was registered at all. */
    public static final String REASON_NO_PARTICIPANT = "NO_PARTICIPANT";
    /** Participants existed, but none claimed the turn and no default owner resolved. */
    public static final String REASON_NO_OWNER = "NO_OWNER";

    public DialogueArbitrationDecision decide(
            List<DialogueParticipantDescriptor> participants,
            List<DialogueClaim> claims,
            Optional<DialogueParticipantDescriptor> defaultOwner
    ) {
        return decide(participants, claims, Optional.empty(), defaultOwner);
    }

    /**
     * @param leaseHolder  the participant that still holds the turn from the previous round, if any
     * @param defaultOwner who owns a turn with no claim and no lease; empty means nobody does
     */
    public DialogueArbitrationDecision decide(
            List<DialogueParticipantDescriptor> participants,
            List<DialogueClaim> claims,
            Optional<DialogueParticipantDescriptor> leaseHolder,
            Optional<DialogueParticipantDescriptor> defaultOwner
    ) {
        if (participants == null || participants.isEmpty()) {
            return DialogueArbitrationDecision.rejected(REASON_NO_PARTICIPANT);
        }
        Map<String, DialogueParticipantDescriptor> participantsByKey = participants.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        participant -> key(participant.moduleId(), participant.participantId()),
                        Function.identity(),
                        (left, right) -> left));
        Optional<DialogueClaim> bestClaim = (claims == null ? List.<DialogueClaim>of() : claims).stream()
                .filter(claim -> claim != null && participantsByKey.containsKey(claim.qualifiedKey()))
                .max(claimComparator());
        if (bestClaim.isPresent()) {
            DialogueClaim claim = bestClaim.get();
            return DialogueArbitrationDecision.accepted(
                    participantsByKey.get(claim.qualifiedKey()), claim, REASON_HARD_CLAIM);
        }
        if (leaseHolder != null && leaseHolder.isPresent()) {
            DialogueParticipantDescriptor registered = registered(participantsByKey, leaseHolder.get());
            if (registered != null) {
                return DialogueArbitrationDecision.accepted(registered, null, REASON_LEASE_CONTINUED);
            }
        }
        return defaultOwner
                .map(owner -> registered(participantsByKey, owner))
                .filter(Objects::nonNull)
                .map(owner -> DialogueArbitrationDecision.accepted(owner, null, REASON_DEFAULT_OWNER))
                .orElseGet(() -> DialogueArbitrationDecision.rejected(REASON_NO_OWNER));
    }

    private static DialogueParticipantDescriptor registered(
            Map<String, DialogueParticipantDescriptor> participantsByKey,
            DialogueParticipantDescriptor candidate
    ) {
        if (candidate == null) {
            return null;
        }
        // Only the descriptor that is registered right now may own the turn; a stale holder object could
        // point at a route the module no longer provides.
        return participantsByKey.get(key(candidate.moduleId(), candidate.participantId()));
    }

    private Comparator<DialogueClaim> claimComparator() {
        return Comparator.comparingInt((DialogueClaim claim) -> claim.source().ordinal())
                .thenComparingInt(DialogueClaim::priority)
                .thenComparing(DialogueClaim::participantId, Comparator.reverseOrder())
                .thenComparing(DialogueClaim::moduleId, Comparator.reverseOrder());
    }

    static String key(String moduleId, String participantId) {
        return (moduleId == null ? "" : moduleId.trim()) + ":" + (participantId == null ? "" : participantId.trim());
    }
}