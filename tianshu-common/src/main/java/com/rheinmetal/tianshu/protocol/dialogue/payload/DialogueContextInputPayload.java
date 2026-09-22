package com.rheinmetal.tianshu.protocol.dialogue.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

/**
 * 上下文型对话输入。外部模块用它把“自己产生的、只需要被记住的话语”交给 AX，
 * 例如 NPC 之间的对白、任务播报或环境台词。
 *
 * <p>它不请求回答：AX 只把内容并入近期对话上下文，不调用 LLM、不产生语音、不占用会话。
 * 需要 AX 回应的输入必须走 {@code AX.DIALOGUE_INPUT}，并由 IA 仲裁决定 owner。</p>
 *
 * <p>{@code sourceId} 由协议信封表达，标识这段话的产出模块；{@code sourceTurnId}
 * 用于同一模块内稳定标识这段话语。AX 不按它去重：同一 id 重复投递会产生重复上下文条目，
 * 因此调用方应保证自己只在内容真正发生时投递一次。</p>
 */
public record DialogueContextInputPayload(
        String speakerName,
        String messageText,
        String sourceTurnId
) implements ITianshuPayload {
    public DialogueContextInputPayload {
        speakerName = sanitize(speakerName);
        sourceTurnId = sanitize(sourceTurnId);
        messageText = messageText == null ? "" : messageText.trim();
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
