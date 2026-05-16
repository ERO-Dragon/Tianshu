package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record TtsControlPayload(Action action, String targetRequestId, String targetSource, String reason) implements ITianshuPayload {
    public enum Action {
        STOP,
        STOP_SOURCE,
        RELOAD_MODEL
    }

    public TtsControlPayload(Action action, String targetRequestId, String reason) {
        this(action, targetRequestId, "", reason);
    }

    public TtsControlPayload {
        action = action == null ? Action.STOP : action;
        targetRequestId = targetRequestId == null ? "" : targetRequestId.trim();
        targetSource = targetSource == null ? "" : targetSource.trim();
        reason = reason == null ? "" : reason.trim();
    }
}
