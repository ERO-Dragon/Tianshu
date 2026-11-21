package com.rheinmetal.tianshu.dubhe.module.memory;

import com.rheinmetal.tianshu.presence.payload.PresenceWorldEventPayload;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheAttachedWorldEvent;

public final class DubheWorldEventMemoryPolicy {
    public DubheWorldEventMemoryAction actionFor(DubheAttachedWorldEvent event) {
        if (event == null || event.isEmpty()) {
            return DubheWorldEventMemoryAction.IGNORE;
        }
        if (PresenceWorldEventPayload.EVENT_ADVANCEMENT_UNLOCKED.equals(event.eventType())) {
            return DubheWorldEventMemoryAction.ATTACH_AND_CREATE_DIRECT_EVENT;
        }
        return DubheWorldEventMemoryAction.ATTACH_ONLY;
    }

    public String sourceKindFor(DubheAttachedWorldEvent event) {
        if (event == null || event.eventType().isBlank()) {
            return "attached_world_event";
        }
        return "world_" + event.eventType();
    }
}
