package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record ChatAssistantInterruptPayload(Reason reason, String detail, long occurredAtMillis) implements ITianshuPayload {
    public enum Reason {
        PLAYER_DEATH,
        WORLD_LOGOUT,
        FEATURE_DISABLED,
        CLIENT_SHUTDOWN
    }

    public ChatAssistantInterruptPayload {
        if (reason == null) reason = Reason.CLIENT_SHUTDOWN;
        if (detail == null) detail = "";
        occurredAtMillis = occurredAtMillis > 0L ? occurredAtMillis : System.currentTimeMillis();
    }
}
