package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

public record ModuleStatusPayload(ModuleStatus status) implements ITianshuPayload {
    public ModuleStatusPayload {
        if (status == null) {
            throw new IllegalArgumentException("status cannot be null");
        }
    }
}
