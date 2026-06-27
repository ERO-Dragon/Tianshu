package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.function.ia.context.DialogueContextFrame;
import com.rheinmetal.tianshu.function.ia.context.DialogueContextProvider;
import com.rheinmetal.tianshu.function.ia.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.function.ia.context.DialogueEntityRef;
import com.rheinmetal.tianshu.function.ia.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;

import java.util.List;

public final class PresenceContextProvider implements DialogueContextProvider {
    private final PresenceStateStore stateStore;

    public PresenceContextProvider(PresenceStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @Override
    public DialogueContextFrame capture(String playerId) {
        return capture(playerId, List.of());
    }

    @Override
    public DialogueContextFrame capture(String playerId, List<DialogueParticipantDescriptor> participants) {
        return toDialogueFrame(playerId, stateStore.contextSnapshot());
    }

    private DialogueContextFrame toDialogueFrame(String playerId, PresenceContextSnapshot snapshot) {
        PresenceContextSnapshot effective = snapshot == null ? PresenceContextSnapshot.empty() : snapshot;
        PresenceTargetSnapshot target = effective.crosshairTarget();
        DialogueEntityRef entityRef = target.present()
                ? new DialogueEntityRef(target.entityId(), target.entityTypeId(), target.displayName(), target.distance(), target.crosshairTarget())
                : null;
        DialogueInteractionHints hints = new DialogueInteractionHints(
                effective.heldItemId(),
                target.present() && target.crosshairTarget(),
                effective.interactionKeyDown(),
                effective.sneaking(),
                target.present() ? target.distance() : 0.0D,
                List.of()
        );
        DialogueContextSnapshot contextSnapshot = new DialogueContextSnapshot(
                cleanPlayerId(playerId, effective.playerId()),
                effective.dimensionId(),
                entityRef == null ? List.of() : List.of(entityRef),
                effective.equippedItemIds(),
                effective.facts()
        );
        return new DialogueContextFrame(hints, contextSnapshot);
    }

    private String cleanPlayerId(String requested, String fallback) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }
}
