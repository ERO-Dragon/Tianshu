package com.rheinmetal.tianshu.function.auxilium.core.prompt;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 提示词装配器。区分四类内容：
 * <ul>
 *   <li>上下文段（{@link #addContextSection}）：**逐字节恒定**的身份与规则，build 时合并为唯一一条
 *       system 消息置于消息列表开头。任何每轮都会变化的内容都不得进入这里，否则整条前缀失效。</li>
 *   <li>摘要块（{@link #addSummaryBlock}）：累积的单元摘要，**独立成一条消息**紧跟在 system 之后。
 *       它属于可缓存前缀的一部分，只有换代（generation swap）才允许它的字节改变。</li>
 *   <li>对话流（{@link #addDialogueTurn}）：历史对话轮次与当前输入，
 *       以 user/assistant 角色按时间顺序追加到 system 消息之后。</li>
 *   <li>尾部段（{@link #addTailSection}）：每轮都会变的动态资料（游戏状态、检索记忆），
 *       作为独立的 user 消息排在对话流之后。放在尾部是刻意的：它只污染自己，
 *       前面的 system 与摘要块仍然逐字节命中缓存。</li>
 * </ul>
 */
public final class AXPromptAssemblyBuilder {
    private final List<String> contextSections = new ArrayList<>();
    private final List<LLMPromptRequestPayload.MessageItemPayload> dialogueTurns = new ArrayList<>();
    private final List<String> tailSections = new ArrayList<>();
    private String summaryBlock = "";
    private String currentInput = "";

    public void addContextSection(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        contextSections.add(content.strip());
    }

    /**
     * 摘要块：紧跟在 system 之后的独立消息。
     *
     * <p>它不能并进 system（那会让"换代"退回成"从第 0 个 token 起全 miss"），也不能落进尾部
     * （那会让它每轮都变、前缀永远不命中）。</p>
     */
    public void addSummaryBlock(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        summaryBlock = content.strip();
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
        if (!summaryBlock.isBlank()) {
            assembled.add(LLMPromptRequestPayload.MessageItemPayload.of("user", summaryBlock));
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
