package com.rheinmetal.tianshu.function.auxilium.core.delegate;

import java.util.List;

/**
 * Complex run 的结论（设计 §5.6）：只有"≤3 句正文 + 可选来源短标识"，其它一律丢弃（A2）。
 *
 * @param text    结论正文，已按 §5.6 约束裁剪
 * @param sources 可选来源短标识（不是命中列表、不是原始材料）
 */
public record AXDelegateConclusion(String text, List<String> sources) {

    public AXDelegateConclusion {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("conclusion text must not be blank");
        }
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
