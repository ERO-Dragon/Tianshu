package com.rheinmetal.tianshu.function.auxilium.module.memory;

import com.rheinmetal.tianshu.function.auxilium.module.memory.summary.AXMemorySummaryBlock;

import java.util.List;

/**
 * 一次装配时记忆侧的全部输入：检索命中的事件摘要，以及累积的摘要块。
 *
 * <p>摘要块（{@link #summaryBlock()}）替换了旧的"最近 N 个 STM 块"槽位：旧槽位每轮都进尾部、
 * 每轮都可能变；摘要块属于可缓存前缀，只有换代才变。</p>
 *
 * <p>这里<b>不再有人格字段</b>：人设是字节冻结的系统提示词（{@code S}）的一部分，运行期不可变。留一个
 * "可配置但从不渲染"的人格字段只会让人以为能配。</p>
 */
public record AXMemorySnapshot(
        List<AXMemoryBlockView> retrievedPlayerMemoryBlocks,
        AXMemorySummaryBlock summaryBlock
) {
    public AXMemorySnapshot {
        retrievedPlayerMemoryBlocks = normalizeBlocks(retrievedPlayerMemoryBlocks);
        summaryBlock = summaryBlock == null ? AXMemorySummaryBlock.empty() : summaryBlock;
    }

    private static List<AXMemoryBlockView> normalizeBlocks(List<AXMemoryBlockView> blocks) {
        return blocks == null ? List.of() : blocks.stream()
                .filter(view -> view != null && !view.isEmpty())
                .toList();
    }

    public static AXMemorySnapshot empty() {
        return new AXMemorySnapshot(List.of(), AXMemorySummaryBlock.empty());
    }

    public AXMemorySnapshot withRetrievedPlayerMemoryBlocks(List<AXMemoryBlockView> blocks) {
        return new AXMemorySnapshot(blocks, summaryBlock);
    }

    public AXMemorySnapshot withSummaryBlock(AXMemorySummaryBlock block) {
        return new AXMemorySnapshot(retrievedPlayerMemoryBlocks, block);
    }
}
