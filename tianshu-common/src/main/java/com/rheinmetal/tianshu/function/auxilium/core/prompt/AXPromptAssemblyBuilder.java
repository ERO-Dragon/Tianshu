package com.rheinmetal.tianshu.function.auxilium.core.prompt;

import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 提示词装配器。区分三类内容：
 * <ul>
 *   <li>上下文段（{@link #addContextSection}）：**逐字节恒定**的身份与规则，build 时合并为唯一一条
 *       system 消息置于消息列表开头。任何每轮都会变化的内容都不得进入这里，否则整条前缀失效。</li>
 *   <li>对话流（{@link #addDialogueTurn}）：历史对话轮次与当前输入，
 *       以 user/assistant 角色按时间顺序追加到 system 消息之后。</li>
 *   <li>尾部段（{@link #addTailSection}）：每轮都会变的动态资料（游戏状态、检索记忆），
 *       作为独立的 user 消息排在对话流之后。放在尾部是刻意的：它只污染自己，
 *       前面的 system 与历史仍然逐字节命中缓存。</li>
 * </ul>
 */
public final class AXPromptAssemblyBuilder {
    private final List<String> contextSections = new ArrayList<>();
    private final List<LLMPromptRequestPayload.MessageItemPayload> dialogueTurns = new ArrayList<>();
    private final List<String> tailSections = new ArrayList<>();
    private String currentInput = "";

    public void addContextSection(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        contextSections.add(content.strip());
    }

    /** 每轮可变的动态资料；排在对话流之后，绝不并入 system。 */
    public void addTailSection(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        tailSections.add(content.strip());
    }

    /**
     * 当前这轮的玩家输入。独立成槽位，保证它永远排在最后——
     * 动态资料（尾部段）必须落在它之前，否则模型会先看到"问题"再看"资料"。
     */
    public void addCurrentInput(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        currentInput = content.strip();
    }

    public void addDialogueTurn(String role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        String normalized = switch (role == null ? "" : role.trim().toLowerCase()) {
            case "assistant" -> "assistant";
            default -> "user";
        };
        dialogueTurns.add(LLMPromptRequestPayload.MessageItemPayload.of(normalized, content));
    }

    public AXPromptAssembly build() {
        List<LLMPromptRequestPayload.MessageItemPayload> assembled = new ArrayList<>();
        if (!contextSections.isEmpty()) {
            String systemContent = String.join("\n\n", contextSections);
            assembled.add(LLMPromptRequestPayload.MessageItemPayload.of("system", systemContent));
        }
        assembled.addAll(dialogueTurns);
        for (String tail : tailSections) {
            assembled.add(LLMPromptRequestPayload.MessageItemPayload.of("user", tail));
        }
        if (!currentInput.isBlank()) {
            assembled.add(LLMPromptRequestPayload.MessageItemPayload.of("user", currentInput));
        }
        return new AXPromptAssembly(assembled);
    }
}
