package com.rheinmetal.tianshu.function.auxilium.module.memory;

import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXAttachedWorldEvent;

public final class AXWorldEventMemoryPolicy {
    public AXWorldEventMemoryAction actionFor(AXAttachedWorldEvent event) {
        if (event == null || event.isEmpty()) {
            return AXWorldEventMemoryAction.IGNORE;
        }
        if (PresenceWorldEventPayload.EVENT_ADVANCEMENT_UNLOCKED.equals(event.eventType())) {
            return AXWorldEventMemoryAction.ATTACH_AND_CREATE_DIRECT_EVENT;
        }
        return AXWorldEventMemoryAction.ATTACH_ONLY;
    }

    public String sourceKindFor(AXAttachedWorldEvent event) {
        if (event == null || event.eventType().isBlank()) {
            return "attached_world_event";
        }
        return "world_" + event.eventType();
    }
}
