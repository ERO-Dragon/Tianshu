package com.rheinmetal.tianshu.function.ia.context;

import com.rheinmetal.tianshu.function.ia.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimConditionType;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimMode;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimRule;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;

import java.util.LinkedHashSet;
import java.util.List;

public record DialogueEntityInterest(List<String> entityTypeIds, double maxDistance) {
    public DialogueEntityInterest {
        entityTypeIds = entityTypeIds == null ? List.of() : List.copyOf(entityTypeIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList());
        maxDistance = Math.max(0.0D, maxDistance);
    }

    public boolean active() {
        return maxDistance > 0.0D && !entityTypeIds.isEmpty();
    }

    public static DialogueEntityInterest fromParticipants(List<DialogueParticipantDescriptor> participants) {
        if (participants == null || participants.isEmpty()) {
            return new DialogueEntityInterest(List.of(), 0.0D);
        }
        LinkedHashSet<String> entityTypeIds = new LinkedHashSet<>();
        double maxDistance = 0.0D;
        for (DialogueParticipantDescriptor participant : participants) {
            if (participant == null || participant.claimProfile().mode() != DialogueClaimMode.RULES) {
                continue;
            }
            for (DialogueClaimRule rule : participant.claimProfile().rules()) {
                for (DialogueClaimCondition condition : rule.conditions()) {
                    if (condition.type() != DialogueClaimConditionType.NEAREST_ENTITY_WITHIN || condition.maxDistance() <= 0.0D) {
                        continue;
                    }
                    entityTypeIds.addAll(condition.values());
                    maxDistance = Math.max(maxDistance, condition.maxDistance());
                }
            }
        }
        return new DialogueEntityInterest(List.copyOf(entityTypeIds), maxDistance);
    }
}
