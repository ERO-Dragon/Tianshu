package com.rheinmetal.tianshu.function.auxilium.module.memory.compression;

import java.util.List;

/**
 * 一次一段式压缩调用的解析结果（三要素 + 降级诊断）。
 *
 * <p>字段级降级：三个字段各自独立解析，坏的字段留空、好的字段照常提交，绝不整体丢弃
 * （记忆设计 §5.2 R6 / §10 G5）。{@link #diagnostics()} 是稳定的英文原因码，用于测试与后续
 * 观测接线；它不参与落库。</p>
 */
public record AXMemoryCompressionResult(
        int schemaVersion,
        String summary,
        List<AXMemoryCompressedEvent> events,
        List<AXMemoryCompressedPreference> preferences,
        List<String> diagnostics
) {
    public AXMemoryCompressionResult {
        summary = summary == null ? "" : summary.trim();
        events = events == null ? List.of() : List.copyOf(events);
        preferences = preferences == null ? List.of() : List.copyOf(preferences);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public boolean hasSummary() {
        return !summary.isBlank();
    }

    /**
     * 是否没有任何可提交内容。
     *
     * <p>为真时调用方必须<b>不消费</b> raw 原始轮次：没有摘要、没有事件、没有偏好就没法证明这个
     * 压缩单元被处理过，原文必须留在窗口里等下一次（记忆设计 §4.2.1 的"原文不可篡改"）。
     * 这与"模型调用失败"用同一条出路，避免无产物却丢原文。</p>
     */
    public boolean isEmpty() {
        return !hasSummary() && events.isEmpty() && preferences.isEmpty();
    }

    public boolean hasDiagnostic(String code) {
        return code != null && diagnostics.contains(code);
    }
}
