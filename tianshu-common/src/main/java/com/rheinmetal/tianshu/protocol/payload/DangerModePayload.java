package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.Priority;

public record DangerModePayload(boolean active, Priority level, String source, String reason, long changedAt) implements ITianshuPayload {
    public DangerModePayload {
        level = level == null ? Priority.NORMAL : level;
        source = source == null || source.isBlank() ? "unknown" : source;
        reason = reason == null ? "" : reason;
        changedAt = changedAt > 0 ? changedAt : System.currentTimeMillis();
    }
}
