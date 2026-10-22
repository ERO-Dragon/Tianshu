package com.rheinmetal.tianshu.function.auxilium.module.memory.compression;

import com.rheinmetal.tianshu.function.auxilium.module.memory.preference.AXPlayerPreference;

import java.util.List;

/**
 * 一段式压缩产出的一条偏好。
 *
 * <p>只有 {@link #confidence()} 达到明示门槛的条目才会被构造出来（对应设计 §4.5 的
 * {@code confidence=explicit} 判据）；落库后的形态是 {@link AXPlayerPreference}，归属键是玩家
 * UUID，不是世界。</p>
 */
public record AXMemoryCompressedPreference(
        String fact,
        double confidence,
        List<String> turnRefs
) {
    public AXMemoryCompressedPreference {
        fact = fact == null ? "" : fact.trim();
        confidence = Math.max(AXPlayerPreference.MIN_CONFIDENCE, Math.min(AXPlayerPreference.MAX_CONFIDENCE, confidence));
        turnRefs = turnRefs == null ? List.of() : turnRefs.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
