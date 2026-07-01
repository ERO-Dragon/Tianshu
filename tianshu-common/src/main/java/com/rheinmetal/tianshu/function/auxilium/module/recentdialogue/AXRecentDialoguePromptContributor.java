package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptAssemblyBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptBuildContext;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptContributor;

import java.util.Comparator;
import java.util.List;

/**
 * 近期对话贡献器。将历史轮次按时间顺序展开为对话流（user/assistant 消息序列），
 * 而非塞进 system 段。这符合主流模型 chat 模板对 system 角色仅在开头的约束。
 * <p>
 * 角色映射：
 * <ul>
 *   <li>assistant → assistant 消息</li>
 *   <li>game_chat（其他玩家聊天）→ user 消息，内容用 {@code <chat speaker="...">} 包裹，
 *       帮助模型区分"环境聊天事件"与"当前玩家指令"，避免小模型对历史 user 消息一并作答</li>
 *   <li>user/world_event → user 消息（当前玩家与 AX 的直接对话）</li>
 * </ul>
 */
public final class AXRecentDialoguePromptContributor implements AXPromptContributor {
    public static final String SECTION_ID = "recent_dialogue";

    @Override
    public String sectionId() {
        return SECTION_ID;
    }

    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        if (context.context() == null || context.context().recentDialogue() == null || context.budget().maxShortTermTurns() <= 0) {
            return;
        }
        List<AXRawTurn> turns = context.context().recentDialogue().turns();
        if (turns == null || turns.isEmpty()) {
            return;
        }
        List<AXRawTurn> selected = turns.stream()
                .filter(turn -> turn != null && !turn.isEmpty())
                .skip(Math.max(0, turns.size() - context.budget().maxShortTermTurns()))
                .sorted(Comparator.comparingLong(AXRawTurn::createdAtMillis))
                .toList();
        for (AXRawTurn turn : selected) {
            String content = renderTurnContent(turn);
            if (content.isBlank()) {
                continue;
            }
            builder.addDialogueTurn(dialogueRole(turn), content);
        }
    }

    private String dialogueRole(AXRawTurn turn) {
        if (turn.assistantRole()) {
            return "assistant";
        }
        return "user";
    }

    private String renderTurnContent(AXRawTurn turn) {
        String message = turn.content();
        if (message == null || message.isBlank()) {
            return "";
        }
        if (turn.gameChatRole()) {
            String speaker = turn.speakerName();
            if (speaker == null || speaker.isBlank()) {
                speaker = "unknown";
            }
            return "<chat speaker=\"" + speaker.trim() + "\">" + message.trim() + "</chat>";
        }
        return message.trim();
    }
}
