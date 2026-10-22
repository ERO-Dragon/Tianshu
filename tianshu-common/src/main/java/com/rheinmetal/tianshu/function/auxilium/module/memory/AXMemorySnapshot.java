package com.rheinmetal.tianshu.function.auxilium.module.memory;

import com.rheinmetal.tianshu.function.auxilium.module.memory.summary.AXMemorySummaryBlock;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.List;

/**
 * 一次装配时记忆侧的全部输入：人格、检索命中的事件摘要、以及累积的摘要块。
 *
 * <p>摘要块（{@link #summaryBlock()}）替换了旧的"最近 N 个 STM 块"槽位：旧槽位每轮都进尾部、
 * 每轮都可能变；摘要块属于可缓存前缀，只有换代才变。</p>
 */
public record AXMemorySnapshot(
        String persona,
        List<AXMemoryBlockView> retrievedPlayerMemoryBlocks,
        AXMemorySummaryBlock summaryBlock
) {
    public AXMemorySnapshot {
        persona = persona == null || persona.isBlank() ? defaultPersona(AXPromptLanguage.EN_US) : persona.trim();
        retrievedPlayerMemoryBlocks = normalizeBlocks(retrievedPlayerMemoryBlocks);
        summaryBlock = summaryBlock == null ? AXMemorySummaryBlock.empty() : summaryBlock;
    }

    public AXMemorySnapshot(String persona, AXMemorySummaryBlock summaryBlock) {
        this(persona, List.of(), summaryBlock);
    }

    private static List<AXMemoryBlockView> normalizeBlocks(List<AXMemoryBlockView> blocks) {
        return blocks == null ? List.of() : blocks.stream()
                .filter(view -> view != null && !view.isEmpty())
                .toList();
    }

    public static AXMemorySnapshot empty(AXScope scope) {
        return new AXMemorySnapshot(defaultPersona(AXPromptLanguage.EN_US), List.of(), AXMemorySummaryBlock.empty());
    }

    public static AXMemorySnapshot empty(AXScope scope, AXPromptLanguage language) {
        return new AXMemorySnapshot(defaultPersona(language), List.of(), AXMemorySummaryBlock.empty());
    }

    public static String defaultPersona(AXPromptLanguage language) {
        return "";
    }

    public AXMemorySnapshot withRetrievedPlayerMemoryBlocks(List<AXMemoryBlockView> blocks) {
        return new AXMemorySnapshot(persona, blocks, summaryBlock);
    }

    public AXMemorySnapshot withSummaryBlock(AXMemorySummaryBlock block) {
        return new AXMemorySnapshot(persona, retrievedPlayerMemoryBlocks, block);
    }
}
