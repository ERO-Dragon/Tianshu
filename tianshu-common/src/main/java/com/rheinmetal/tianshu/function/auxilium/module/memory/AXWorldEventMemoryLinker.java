package com.rheinmetal.tianshu.function.auxilium.module.memory;

import java.util.List;
import java.util.Objects;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXAttachedWorldEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlock;

public final class AXWorldEventMemoryLinker {
    private final AXWorldEventMemoryPolicy policy;

    public AXWorldEventMemoryLinker(AXWorldEventMemoryPolicy policy) {
        this.policy = policy == null ? new AXWorldEventMemoryPolicy() : policy;
    }

    public List<String> attachedEventIds(List<AXAttachedWorldEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream()
                .filter(event -> event != null && !event.isEmpty())
                .map(AXAttachedWorldEvent::id)
                .distinct()
                .toList();
    }

    public List<AXMemoryEvent> directEventsFor(AXStmBlock stm, List<AXAttachedWorldEvent> events) {
        if (stm == null || stm.isEmpty() || events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream()
                .filter(event -> event != null && !event.isEmpty())
                .filter(event -> policy.actionFor(event) == AXWorldEventMemoryAction.ATTACH_AND_CREATE_DIRECT_EVENT)
                .map(event -> event.toMemoryEvent(stm.id(), policy.sourceKindFor(event)))
                .filter(Objects::nonNull)
                .filter(event -> !event.isEmpty())
                .toList();
    }
}
