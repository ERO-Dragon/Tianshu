package com.rheinmetal.tianshu.dubhe.module.memory.summary;

import com.rheinmetal.tianshu.dubhe.module.memory.DubheMemoryBlockView;

import java.util.List;

/**
 * 摘要块在某一轮的渲染视图：<b>新→旧</b>排列的单元摘要、渲染成本、以及被预算挤出的旧单元数量。
 *
 * <p>这是<b>派生量</b>：账本（{@link DubheMemorySummaryBlockStore}）+ 摘要库（{@code DubheStmBlockStore}）
 * + 容量预算（{@code DubheMemoryWindowPolicy.summaryBlockTokenBudget}）算出来的，不落盘。换代之前，
 * 同样的输入必然算出逐字节相同的结果——前缀缓存正是靠这一点。</p>
 *
 * <p>{@link #droppedTotal()} 只统计"已晋升但装不下"的单元，它们仍在记忆库里、事件照常可检索；
 * 丢掉的是渲染，不是记忆。</p>
 */
public record DubheMemorySummaryBlock(
        int generation,
        List<DubheMemoryBlockView> units,
        int summaryBlockTokens,
        int droppedTotal
) {
    public DubheMemorySummaryBlock {
        generation = Math.max(0, generation);
        units = units == null ? List.of() : units.stream()
                .filter(view -> view != null && !view.isEmpty())
                .toList();
        summaryBlockTokens = Math.max(0, summaryBlockTokens);
        droppedTotal = Math.max(0, droppedTotal);
    }

    public static DubheMemorySummaryBlock empty() {
        return new DubheMemorySummaryBlock(0, List.of(), 0, 0);
    }

    public boolean isEmpty() {
        return units.isEmpty();
    }

    /** 块内单元份数，观测字段 {@code summariesInBlock}。 */
    public int summariesInBlock() {
        return units.size();
    }
}
