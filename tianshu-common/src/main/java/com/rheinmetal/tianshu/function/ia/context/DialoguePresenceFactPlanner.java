package com.rheinmetal.tianshu.function.ia.context;

import com.rheinmetal.tianshu.function.ia.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimConditionType;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimMode;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimRule;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.PresenceContextFactIds;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DialoguePresenceFactPlanner {
    public List<String> plan(List<DialogueParticipantDescriptor> participants) {
        LinkedHashSet<String> factIds = new LinkedHashSet<>();
        factIds.add(PresenceContextFactIds.INTERACTION_CONTEXT);
        if (participants == null || participants.isEmpty()) {
            return List.copyOf(factIds);
        }
        for (DialogueParticipantDescriptor participant : participants) {
            collect(participant, factIds);
        }
        return List.copyOf(factIds);
    }

    private void collect(DialogueParticipantDescriptor participant, Set<String> factIds) {
        if (participant == null) {
            return;
        }
        DialogueClaimProfile profile = participant.claimProfile();
        if (profile == null || profile.mode() != DialogueClaimMode.RULES) {
            return;
        }
        for (DialogueClaimRule rule : profile.rules()) {
            if (rule == null || rule.conditions().isEmpty()) {
                continue;
            }
            for (DialogueClaimCondition condition : rule.conditions()) {
                collect(condition, factIds);
            }
        }
    }

    private void collect(DialogueClaimCondition condition, Set<String> factIds) {
        if (condition == null || condition.type() != DialogueClaimConditionType.CONTEXT_FACT) {
            return;
        }
        String factKey = condition.factKey();
        if (factKey == null || factKey.isBlank()) {
            return;
        }
        String normalized = factKey.trim();
        if (normalized.equals(PresenceContextFactIds.PLAYER_STATUS)
                || normalized.equals(PresenceContextFactIds.PLAYER_INVENTORY)
                || normalized.equals(PresenceContextFactIds.PLAYER_ACTIVE_EFFECTS)
                || normalized.equals(PresenceContextFactIds.WORLD_ENVIRONMENT)
                || normalized.equals(PresenceContextFactIds.INTERACTION_CONTEXT)) {
            factIds.add(normalized);
        }
    }
}
