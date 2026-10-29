package com.rheinmetal.tianshu.function.ia.context;

import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextFrame;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueEntityRef;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.protocol.payload.PresenceInteractionContextPayload;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the pushed interaction digest into the context a turn is arbitrated against.
 *
 * <p>The digest is the same frame the pre-winner on screen was computed from, so using it here is what keeps "the
 * participant shown" and "the participant that gets the turn" from drifting apart. It is a deliberately small
 * record: it carries exactly the interaction plus the names a delivery needs, so the frame it produces is the whole
 * context of a turn — there is no second capture to merge with, and no field of it can disagree with the marker.</p>
 */
public final class DialogueInteractionContextMapper {
    public DialogueContextFrame toFrame(PresenceInteractionContextPayload payload) {
        if (payload == null) {
            return DialogueContextFrame.empty("");
        }
        List<DialogueEntityRef> entityRefs = payload.crosshairHit()
                ? List.of(new DialogueEntityRef(
                        payload.crosshairEntityId(),
                        payload.crosshairEntityTypeId(),
                        payload.crosshairEntityDisplayName(),
                        payload.crosshairDistance(),
                        true))
                : List.of();
        DialogueInteractionHints hints = new DialogueInteractionHints(
                payload.heldItemId(),
                payload.crosshairHit(),
                payload.interactionKeyDown(),
                payload.sneaking(),
                payload.crosshairHit() ? payload.crosshairDistance() : 0.0D
        );
        DialogueContextSnapshot snapshot = new DialogueContextSnapshot(
                payload.playerId(),
                payload.dimensionId(),
                entityRefs,
                payload.equippedItemIds(),
                Map.of()
        );
        return new DialogueContextFrame(hints, snapshot, displayNames(payload));
    }

    /**
     * The names a delivery needs, keyed by the id the delivery carries.
     *
     * <p>Without this map the wording would show registry ids for anything a frozen digest described, because the
     * digest replaces the capture that used to supply them. Only pairs where both halves are present are recorded:
     * a missing name degrades to the id's own words, a fabricated one would be worse.</p>
     */
    private static Map<String, String> displayNames(PresenceInteractionContextPayload payload) {
        Map<String, String> names = new LinkedHashMap<>();
        pair(names, payload.dimensionId(), payload.dimensionDisplayName());
        pair(names, payload.heldItemId(), payload.heldItemDisplayName());
        pair(names, payload.crosshairEntityTypeId(), payload.crosshairEntityDisplayName());
        return Map.copyOf(names);
    }

    private static void pair(Map<String, String> names, String id, String name) {
        if (!id.isBlank() && !name.isBlank()) {
            names.putIfAbsent(id, name);
        }
    }
}
