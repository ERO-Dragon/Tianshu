package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.List;

public record AXMemorySnapshot(
        String persona,
        List<AXMemoryBlockView> playerMemoryBlocks,
        List<AXRawTurn> recentDialogueTurns
) {
    public AXMemorySnapshot {
        persona = persona == null || persona.isBlank() ? defaultPersona(AXPromptLanguage.EN_US) : persona.trim();
        playerMemoryBlocks = playerMemoryBlocks == null ? List.of() : playerMemoryBlocks.stream()
                .filter(view -> view != null && !view.isEmpty())
                .toList();
        recentDialogueTurns = recentDialogueTurns == null ? List.of() : List.copyOf(recentDialogueTurns);
    }

    public static AXMemorySnapshot empty(AXScope scope) {
        return new AXMemorySnapshot(defaultPersona(AXPromptLanguage.EN_US), List.of(), List.of());
    }

    public static AXMemorySnapshot empty(AXScope scope, AXPromptLanguage language) {
        return new AXMemorySnapshot(defaultPersona(language), List.of(), List.of());
    }

    public static String defaultPersona(AXPromptLanguage language) {
        if (language == AXPromptLanguage.EN_US) {
            return "You are the companion chat AX of the Tianshu Minecraft mod. Stay immersive, natural, and concise. Do not invent game state. When the user mentions game actions, only give advice and do not claim that you can directly execute them.";
        }
        return "你是天枢 Minecraft 模组中的随行聊天助手。保持沉浸感，回答自然、简洁；不要编造游戏状态；涉及游戏动作时只提供建议，不声称自己能直接执行。";
    }

    public AXMemorySnapshot withPlayerMemoryBlocks(List<AXMemoryBlockView> blocks) {
        return new AXMemorySnapshot(persona, blocks, recentDialogueTurns);
    }
}
