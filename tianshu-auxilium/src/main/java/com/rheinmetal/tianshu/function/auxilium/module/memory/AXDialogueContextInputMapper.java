package com.rheinmetal.tianshu.function.auxilium.module.memory;

import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueContextInputPayload;

/**
 * 上下文型对话输入到近期对话原始轮次的映射。
 *
 * <p>外部模块的话语以第三方发言人身份进入上下文，与游戏聊天消息共用 {@code game_chat} 角色，
 * 因此不会与 AX/玩家二元对话混淆，也不会触发回答。</p>
 */
public final class AXDialogueContextInputMapper {
    public AXRawTurn map(AXScope scope, String sourceId, DialogueContextInputPayload payload) {
        if (payload == null) {
            return AXRawTurn.gameChat(scope, "", "", 0L, "");
        }
        return AXRawTurn.gameChat(
                scope,
                payload.speakerName(),
                payload.messageText(),
                System.currentTimeMillis(),
                sourceTurnId(sourceId, payload)
        );
    }

    private static String sourceTurnId(String sourceId, DialogueContextInputPayload payload) {
        String normalizedSourceId = sourceId == null || sourceId.isBlank() ? "unknown" : sourceId.trim();
        String explicitTurnId = payload.sourceTurnId();
        if (!explicitTurnId.isBlank()) {
            return "context:" + normalizedSourceId + ":" + explicitTurnId;
        }
        return "context:" + normalizedSourceId + ":" + Integer.toUnsignedString(payload.messageText().hashCode());
    }
}
