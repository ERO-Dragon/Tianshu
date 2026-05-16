package com.rheinmetal.tianshu.function.assistant.memory;

import com.rheinmetal.tianshu.function.assistant.prompt.AssistantPromptLanguage;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;

import java.util.List;

public record AssistantMemorySnapshot(
        String persona,
        List<String> longTermUserMemory,
        List<String> conversationSummary,
        List<ShortTermMemoryBlock> shortTermMemoryBlocks,
        List<ConversationTurn> shortTermTurns
) {
    public AssistantMemorySnapshot {
        persona = persona == null || persona.isBlank() ? defaultPersona(AssistantPromptLanguage.ZH_CN) : persona.trim();
        longTermUserMemory = longTermUserMemory == null ? List.of() : List.copyOf(longTermUserMemory);
        conversationSummary = conversationSummary == null ? List.of() : List.copyOf(conversationSummary);
        shortTermMemoryBlocks = shortTermMemoryBlocks == null ? List.of() : List.copyOf(shortTermMemoryBlocks);
        shortTermTurns = shortTermTurns == null ? List.of() : List.copyOf(shortTermTurns);
    }

    public AssistantMemorySnapshot(String persona, List<String> longTermUserMemory, List<String> conversationSummary, List<ConversationTurn> shortTermTurns) {
        this(persona, longTermUserMemory, conversationSummary, List.of(), shortTermTurns);
    }

    public static AssistantMemorySnapshot empty(AssistantScope scope) {
        return new AssistantMemorySnapshot(defaultPersona(AssistantPromptLanguage.ZH_CN), List.of(), List.of(), List.of(), List.of());
    }

    public static AssistantMemorySnapshot empty(AssistantScope scope, AssistantPromptLanguage language) {
        return new AssistantMemorySnapshot(defaultPersona(language), List.of(), List.of(), List.of(), List.of());
    }

    public static String defaultPersona(AssistantPromptLanguage language) {
        if (language == AssistantPromptLanguage.EN_US) {
            return "You are the companion chat assistant of the Tianshu Minecraft mod. Stay immersive, natural, and concise. Do not invent game state. When the user mentions game actions, only give advice and do not claim that you can directly execute them.";
        }
        return "你是天枢 Minecraft 模组中的随行聊天助手。保持沉浸感，回答自然、简洁；不要编造游戏状态；涉及游戏动作时只提供建议，不声称自己能直接执行。";
    }
}
