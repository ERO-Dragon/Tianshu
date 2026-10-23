package com.rheinmetal.tianshu.function.auxilium.module.memory;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.protocol.payload.PresenceChatMessagePayload;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;

/**
 * Maps a presence chat message into the recent-dialogue history.
 *
 * <p>A chat line from another player is one event among the others: it becomes a {@code world_event} carrying the
 * sender as its speaker, and the renderer writes that speaker into the event body. There is no separate
 * chat-flavoured turn any more, so nothing here has to choose between two event kinds.</p>
 */
public final class AXPresenceChatMessageMapper {
    public AXRawTurn map(AXScope scope, PresenceChatMessagePayload payload) {
        if (payload == null) {
            return AXRawTurn.worldEvent(scope, "", "", 0L);
        }
        return AXRawTurn.worldEvent(
                scope,
                payload.senderName(),
                payload.messageText(),
                System.currentTimeMillis(),
                sourceId(payload)
        );
    }

    private String sourceId(PresenceChatMessagePayload payload) {
        return "presence.chat:" + payload.senderId() + ":" + Integer.toUnsignedString(payload.messageText().hashCode());
    }
}
