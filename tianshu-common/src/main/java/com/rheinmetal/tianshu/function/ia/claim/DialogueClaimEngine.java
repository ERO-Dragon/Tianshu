package com.rheinmetal.tianshu.function.ia.claim;

import com.rheinmetal.tianshu.function.ia.model.DialogueClaim;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.function.ia.payload.DialogueArbitrationRequestPayload;

import java.util.List;

public final class DialogueClaimEngine {
    public List<DialogueClaim> collectLocalClaims(List<DialogueParticipantDescriptor> participants, DialogueArbitrationRequestPayload request) {
        return participants.stream()
                .map(participant -> score(participant, request))
                .filter(claim -> claim.score() > 0.0D || claim.confidence() > 0.0D)
                .toList();
    }

    private DialogueClaim score(DialogueParticipantDescriptor participant, DialogueArbitrationRequestPayload request) {
        double score = 0.0D;
        double confidence = 0.0D;
        if (matchesAny(participant.supportedIntents(), request.matchedHotwords())) {
            score += 0.35D;
            confidence += 0.35D;
        }
        if (matchesAny(participant.supportedItemIds(), request.matchedItemIds())) {
            score += 0.3D;
            confidence += 0.25D;
        }
        if (matchesAny(participant.supportedEntityTypes(), request.matchedEntityRefs())) {
            score += 0.25D;
            confidence += 0.25D;
        }
        if (request.interactionHints().crosshairHit()) {
            score += 0.05D;
        }
        if (request.interactionHints().interactionKeyDown()) {
            score += 0.05D;
        }
        if (participant.supportedIntents().isEmpty() && participant.supportedItemIds().isEmpty() && participant.supportedEntityTypes().isEmpty()) {
            score = Math.max(score, 0.1D);
            confidence = Math.max(confidence, 0.1D);
        }
        return new DialogueClaim(participant.participantId(), score, confidence, participant.priority(), false, "local_score");
    }

    private static boolean matchesAny(List<String> supported, List<String> actual) {
        if (supported == null || supported.isEmpty() || actual == null || actual.isEmpty()) {
            return false;
        }
        return actual.stream().anyMatch(value -> supported.stream().anyMatch(supportedValue -> supportedValue.equalsIgnoreCase(value)));
    }
}
