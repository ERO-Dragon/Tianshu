package com.rheinmetal.tianshu.function.auxilium.module.memory;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import com.rheinmetal.tianshu.function.presence.payload.PresenceWorldEventPayload;

import java.util.Map;

/**
 * Maps a chat line — now one kind of presence world event — into the recent-dialogue history.
 *
 * <p>Chat arrives as an event whose type says "someone spoke" and whose values carry who and what, so this is
 * a projection of one event shape rather than a second payload kind. It lands in history rather than memory
 * because a chat line is something said, and the model reads said things in order.</p>
 */
public final class AXPresenceChatMessageMapper {

    public AXRawTurn map(AXScope scope, PresenceWorldEventPayload payload) {
        if (payload == null) {
            return AXRawTurn.worldEvent(scope, "", "", 0L);
        }
        Map<String, String> values = payload.values();
        String senderId = values.getOrDefault(PresenceWorldEventPayload.VALUE_SENDER_ID, "");
        String senderName = values.getOrDefault(PresenceWorldEventPayload.VALUE_SENDER_NAME, "");
        String text = values.getOrDefault(PresenceWorldEventPayload.VALUE_TEXT, "");
        return AXRawTurn.worldEvent(
                scope,
                senderName,
                text,
                payload.occurredAtMillis(),
                "presence.chat:" + senderId + ":" + Integer.toUnsignedString(text.hashCode())
        );
    }
}
