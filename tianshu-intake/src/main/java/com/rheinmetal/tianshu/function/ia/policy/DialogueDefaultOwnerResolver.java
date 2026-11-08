package com.rheinmetal.tianshu.function.ia.policy;

import com.rheinmetal.tianshu.function.ia.settings.IaDefaultOwnerSettings;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides who owns a turn that no claim and no lease selected.
 *
 * <p>Three answers, in this order: the player turned the default owner off; the player picked one participant;
 * nobody picked, so the participant that declared itself the out-of-box default takes the turn. An explicit pick
 * that is not registered right now answers "nobody" — the choice is the player's, and quietly promoting someone
 * else because the chosen module is missing would be answering a different question than the one they asked.</p>
 *
 * <p>A selection is {@code moduleId:participantId}. A bare participant id is a value from before that encoding
 * and is honored only when it names exactly one candidate: a participant id alone cannot tell two modules apart,
 * and guessing between them would send turns to a module the player did not pick.</p>
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
        String chosen = settings == null ? "" : trim(settings.selection());
        if (!chosen.isBlank()) {
            return resolveSelection(participants, chosen);
        }
        return participants.stream()
                .filter(Objects::nonNull)
                .filter(participant -> participant.canBeDefaultOwner() && participant.factoryDefaultOwner())
                .max(Comparator.comparingInt(DialogueParticipantDescriptor::priority)
                        .thenComparing(DialogueParticipantDescriptor::participantId, Comparator.reverseOrder()));
    }

    private Optional<DialogueParticipantDescriptor> resolveSelection(
            List<DialogueParticipantDescriptor> participants,
            String selection
    ) {
        int separator = selection.indexOf(':');
        if (separator > 0 && separator < selection.length() - 1) {
            String moduleId = selection.substring(0, separator).trim();
            String participantId = selection.substring(separator + 1).trim();
            return participants.stream()
                    .filter(Objects::nonNull)
                    .filter(participant -> participant.canBeDefaultOwner()
                            && moduleId.equals(participant.moduleId())
                            && participantId.equals(participant.participantId()))
                    .findFirst();
        }
        List<DialogueParticipantDescriptor> legacyMatches = participants.stream()
                .filter(Objects::nonNull)
                .filter(participant -> participant.canBeDefaultOwner() && selection.equals(participant.participantId()))
                .limit(2)
                .toList();
        return legacyMatches.size() == 1 ? Optional.of(legacyMatches.get(0)) : Optional.empty();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}