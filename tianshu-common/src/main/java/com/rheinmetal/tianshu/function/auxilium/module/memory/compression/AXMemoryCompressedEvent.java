package com.rheinmetal.tianshu.function.auxilium.module.memory.compression;

import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;

import java.util.List;

/**
 * 一段式压缩产出的一条事件。
 *
 * <p>与 {@link AXMemoryEvent} 的区别：这里是"模型刚给出、尚未落库"的形态，只有事实文本、
 * 静态分与轮级溯源。落库时补齐 world/stm/时间等来源字段。</p>
 *
 * <p><b>不去重、不合并</b>：同一个 {@link #fact()} 出现两次就是两个对象，只要溯源不同就是两次
 * 真实发生（记忆设计 §5.2 R4）。</p>
 */
public record AXMemoryCompressedEvent(
        String fact,
        int importance,
        List<String> turnRefs
) {
    public AXMemoryCompressedEvent {
        fact = fact == null ? "" : fact.trim();
        importance = Math.max(AXMemoryEvent.MIN_IMPORTANCE, Math.min(AXMemoryEvent.MAX_IMPORTANCE, importance));
        turnRefs = turnRefs == null ? List.of() : turnRefs.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
