package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.List;

public record AXMemorySnapshot(
        String persona,
        List<AXMemoryBlockView> retrievedPlayerMemoryBlocks,
        List<AXMemoryBlockView> recentPlayerMemoryBlocks,
        List<AXRawTurn> recentDialogueTurns
) {
    public AXMemorySnapshot {
        persona = persona == null || persona.isBlank() ? defaultPersona(AXPromptLanguage.EN_US) : persona.trim();
        retrievedPlayerMemoryBlocks = normalizeBlocks(retrievedPlayerMemoryBlocks);
        recentPlayerMemoryBlocks = normalizeBlocks(recentPlayerMemoryBlocks);
        recentDialogueTurns = recentDialogueTurns == null ? List.of() : List.copyOf(recentDialogueTurns);
    }

    public AXMemorySnapshot(String persona, List<AXMemoryBlockView> recentPlayerMemoryBlocks, List<AXRawTurn> recentDialogueTurns) {
        this(persona, List.of(), recentPlayerMemoryBlocks, recentDialogueTurns);
    }

    private static List<AXMemoryBlockView> normalizeBlocks(List<AXMemoryBlockView> blocks) {
        return blocks == null ? List.of() : blocks.stream()
                .filter(view -> view != null && !view.isEmpty())
                .toList();
    }

    public static AXMemorySnapshot empty(AXScope scope) {
        return new AXMemorySnapshot(defaultPersona(AXPromptLanguage.EN_US), List.of(), List.of(), List.of());
    }

    public static AXMemorySnapshot empty(AXScope scope, AXPromptLanguage language) {
        return new AXMemorySnapshot(defaultPersona(language), List.of(), List.of(), List.of());
    }

    public static String defaultPersona(AXPromptLanguage language) {
        return "";
    }

    public AXMemorySnapshot withRetrievedPlayerMemoryBlocks(List<AXMemoryBlockView> blocks) {
        return new AXMemorySnapshot(persona, blocks, recentPlayerMemoryBlocks, recentDialogueTurns);
    }

    public AXMemorySnapshot withRecentPlayerMemoryBlocks(List<AXMemoryBlockView> blocks) {
        return new AXMemorySnapshot(persona, retrievedPlayerMemoryBlocks, blocks, recentDialogueTurns);
    }
}
