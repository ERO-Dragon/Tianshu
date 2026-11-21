package com.rheinmetal.tianshu.dubhe.module.recentdialogue;

import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptAssemblyBuilder;
import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptBuildContext;
import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptContributor;

import java.util.Comparator;
import java.util.List;

/**
 * 近期对话贡献器。将历史轮次按时间顺序展开为对话流（user/assistant 消息序列），
 * 而非塞进 system 段。这符合主流模型 chat 模板对 system 角色仅在开头的约束。
 * <p>
 * 角色映射：
 * <ul>
 *   <li>assistant → assistant 消息</li>
 *   <li>world_event（其他玩家聊天、其他模组投递、游戏事件）→ user 消息，内容统一用 {@code <event>} 包裹，
 *       带主体时正文写成"同伴 X 说：…"（模板见 {@code recent_dialogue.event.said}），
 *       让模型把它读成"刚刚发生的事实"，而不是玩家说的话</li>
 *   <li>user → user 消息（当前玩家与 AX 的直接对话）</li>
 * </ul>
 * 包裹与正文的拼装只有一份实现（{@link DubheRawTurnPromptText}），token 计数走的也是它。
 */
public final class DubheRecentDialoguePromptContributor implements DubhePromptContributor {
    public static final String SECTION_ID = "recent_dialogue";

    @Override
    public String sectionId() {
        return SECTION_ID;
    }

    @Override
    public void contribute(DubhePromptBuildContext context, DubhePromptAssemblyBuilder builder) {
        if (context.context() == null || context.context().recentDialogue() == null || context.budget().maxRecentRawDialogueTurns() <= 0) {
            return;
        }
        List<DubheRawTurn> turns = context.context().recentDialogue().turns();
        if (turns == null || turns.isEmpty()) {
            return;
        }
        List<DubheRawTurn> selected = turns.stream()
                .filter(turn -> turn != null && !turn.isEmpty())
                .skip(Math.max(0, turns.size() - context.budget().maxRecentRawDialogueTurns()))
                .sorted(Comparator.comparingLong(DubheRawTurn::createdAtMillis))
                .toList();
        for (DubheRawTurn turn : selected) {
            String content = renderTurnContent(turn, context);
            if (content.isBlank()) {
                continue;
            }
            builder.addDialogueTurn(dialogueRole(turn), content);
        }
    }

    private String dialogueRole(DubheRawTurn turn) {
        if (turn.assistantRole()) {
            return "assistant";
        }
        return "user";
    }

    private String renderTurnContent(DubheRawTurn turn, DubhePromptBuildContext context) {
        return DubheRawTurnPromptText.render(turn, context == null ? null : context.texts());
    }
}
