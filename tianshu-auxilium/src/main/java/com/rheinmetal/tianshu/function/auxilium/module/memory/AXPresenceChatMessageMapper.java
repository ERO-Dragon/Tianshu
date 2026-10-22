package com.rheinmetal.tianshu.function.auxilium.module.memory;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.protocol.payload.PresenceChatMessagePayload;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;

public final class AXPresenceChatMessageMapper {
    public AXRawTurn map(AXScope scope, PresenceChatMessagePayload payload) {
        if (payload == null) {
            return AXRawTurn.gameChat(scope, "", "", 0L, "");
        }
        return AXRawTurn.gameChat(
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
