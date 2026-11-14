package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record RuntimeInterruptPayload(long sessionId, Reason reason, String detail, long occurredAtMillis) implements ITianshuPayload {
    public enum Reason {
        USER_INPUT,
        PLAYER_DEATH,
        WORLD_LOGOUT,
        DIMENSION_CHANGE,
        CLIENT_SHUTDOWN,
        ENGINE_RESTART
    }
}
