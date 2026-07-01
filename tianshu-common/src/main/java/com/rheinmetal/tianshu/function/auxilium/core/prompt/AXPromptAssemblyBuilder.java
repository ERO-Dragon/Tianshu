package com.rheinmetal.tianshu.function.auxilium.core.prompt;

import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 提示词装配器。区分两类内容：
 * <ul>
 *   <li>上下文段（{@link #addContextSection}）：身份、游戏上下文、玩家记忆等静态背景信息，
 *       build 时合并为唯一一条 system 消息置于消息列表开头。</li>
 *   <li>对话流（{@link #addDialogueTurn}）：历史对话轮次与当前输入，
 *       以 user/assistant 角色按时间顺序追加到 system 消息之后。</li>
 * </ul>
 * 这一划分保证 system 角色只出现一次且在最前面，符合主流模型 chat 模板约束。
 */
public final class AXPromptAssemblyBuilder {
    private final List<String> contextSections = new ArrayList<>();
    private final List<LLMPromptRequestPayload.MessageItemPayload> dialogueTurns = new ArrayList<>();

    public void addContextSection(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        contextSections.add(content.strip());
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
        return new AXPromptAssembly(assembled);
    }
}
