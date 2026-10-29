package com.rheinmetal.tianshu.function.ia.policy;

import com.rheinmetal.tianshu.function.ia.settings.IaDefaultOwnerSettings;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Decides who owns a turn that no claim and no lease selected.
 *
 * <p>Three answers, in this order: the player turned the default owner off; the player picked one participant;
 * nobody picked, so the participant that declared itself the out-of-box default takes the turn. An explicit pick
 * that is not registered right now answers "nobody" — the choice is the player's, and quietly promoting someone
 * else because the chosen module is missing would be answering a different question than the one they asked.</p>
 */
public final class DialogueDefaultOwnerResolver {
    public Optional<DialogueParticipantDescriptor> resolve(
            List<DialogueParticipantDescriptor> participants,
            IaDefaultOwnerSettings settings
    ) {
        if (participants == null || participants.isEmpty()) {
            return Optional.empty();
        }
        if (settings != null && settings.isDisabled()) {
            return Optional.empty();
        }
        String chosen = settings == null ? "" : settings.participantId();
        if (!chosen.isBlank()) {
            return participants.stream()
                    .filter(participant -> participant != null
                            && chosen.equals(participant.participantId())
                            && participant.canBeDefaultOwner())
                    .findFirst();
        }
        return participants.stream()
                .filter(participant -> participant != null
                        && participant.canBeDefaultOwner()
                        && participant.factoryDefaultOwner())
                .max(Comparator.comparingInt(DialogueParticipantDescriptor::priority)
                        .thenComparing(DialogueParticipantDescriptor::participantId, Comparator.reverseOrder()));
    }
}