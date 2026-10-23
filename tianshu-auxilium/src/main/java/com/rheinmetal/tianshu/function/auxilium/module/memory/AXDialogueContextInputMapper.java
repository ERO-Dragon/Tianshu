package com.rheinmetal.tianshu.function.auxilium.module.memory;

import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueContextInputPayload;

/**
 * Maps a third-party dialogue input into the recent-dialogue history.
 *
 * <p>Another mod's line is an event like any other non-player source: it becomes a {@code world_event} whose
 * speaker is the third-party name, so it never mixes with the player/AX dialogue and never triggers an answer.
 * The source turn id is kept as the turn's external identity, so a re-delivery keeps the same id.</p>
 */
public final class AXDialogueContextInputMapper {
    public AXRawTurn map(AXScope scope, String sourceId, DialogueContextInputPayload payload) {
        if (payload == null) {
            return AXRawTurn.worldEvent(scope, "", "", 0L, "");
        }
        return AXRawTurn.worldEvent(
                scope,
                payload.speakerName(),
                payload.messageText(),
                System.currentTimeMillis(),
                sourceTurnId(sourceId, payload)
        );
    }

    private static String sourceTurnId(String sourceId, DialogueContextInputPayload payload) {
        String normalizedSourceId = sourceId == null || sourceId.isBlank() ? "unknown" : sourceId.trim();
        String explicitTurnId = payload.sourceTurnId();
        if (!explicitTurnId.isBlank()) {
            return "context:" + normalizedSourceId + ":" + explicitTurnId;
        }
        return "context:" + normalizedSourceId + ":" + Integer.toUnsignedString(payload.messageText().hashCode());
    }
}
