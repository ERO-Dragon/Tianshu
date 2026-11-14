package com.rheinmetal.tianshu.function.auxilium.module.memory;

import java.util.List;
import java.util.Objects;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXAttachedWorldEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;

/**
 * 世界事件与压缩单元之间的接线。
 *
 * <p>直通事件只需要单元的 id（{@code stmId}），不需要一份摘要正文；因此这里收的是 id 而不是
 * {@code AXStmBlock}——摘要字段级降级（该单元没有摘要）时，世界事件仍然要能落成事件，不能因为
 * "没有 STM 块"就丢掉。</p>
 */
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

    public List<AXMemoryEvent> directEventsFor(String stmId, List<AXAttachedWorldEvent> events) {
        if (stmId == null || stmId.isBlank() || events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream()
                .filter(event -> event != null && !event.isEmpty())
                .filter(event -> policy.actionFor(event) == AXWorldEventMemoryAction.ATTACH_AND_CREATE_DIRECT_EVENT)
                .map(event -> event.toMemoryEvent(stmId, policy.sourceKindFor(event)))
                .filter(Objects::nonNull)
                .filter(event -> !event.isEmpty())
                .toList();
    }
}
