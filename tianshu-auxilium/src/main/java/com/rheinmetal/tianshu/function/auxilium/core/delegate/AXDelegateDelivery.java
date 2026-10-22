package com.rheinmetal.tianshu.function.auxilium.core.delegate;

import com.rheinmetal.tianshu.function.auxilium.core.agent.AXRunTermination;

import java.util.List;

/**
 * 一次委派"该播报什么"的句柄（设计 §6.2 / §6.1 通道③）。
 *
 * <p>本片只产出句柄，不出声。两种形态：</p>
 * <ul>
 *   <li><b>回执</b>（{@link #ack}）：{@code kind}/{@code status} 为 {@code null}，{@code utteranceKey} 是
 *       "我查一下"的资源键（§6.2：回执与结论是两条不同资源键）；</li>
 *   <li><b>终态</b>：{@link AXDelegateOutcomeKind#OK} 时念结论正文本身（{@code text}，不再二次改写），
 *       其余终态念兜底句资源键（{@code utteranceKey}）。</li>
 * </ul>
 *
 * <p>句柄<b>按到达顺序（FIFO）</b>交到 {@link AXDelegateDeliverySink}：先回来的先排，谁都不等谁。
 * 正在播报时要不要排队 / 打断是 TTS 层的事，本层不猜，也不丢弃任何一条句柄。</p>
 *
 * @param taskId       投递幂等主键
 * @param kind         终态分类；回执为 {@code null}
 * @param status       原始终态枚举；回执为 {@code null}
 * @param text         结论正文；只有 {@link AXDelegateOutcomeKind#OK} 时非空
 * @param sources      来源短标识
 * @param utteranceKey 兜底句 / 回执的资源键；结论类为 {@code null}
 */
public record AXDelegateDelivery(
        String taskId,
        AXDelegateOutcomeKind kind,
        AXRunTermination status,
        String text,
        List<String> sources,
        String utteranceKey
) {
    public AXDelegateDelivery {
        sources = sources == null ? List.of() : List.copyOf(sources);
        text = text == null ? "" : text;
    }

    /** 回执（§6.2 "我查一下"），不是终态，因此不带 kind / status。 */
    public static AXDelegateDelivery ack(String taskId) {
        return new AXDelegateDelivery(
                taskId, null, null, "", List.of(), AXDelegateOutcomeKind.ACK_SPEECH_KEY);
    }

    /** 是否为回执。 */
    public boolean isAck() {
        return kind == null;
    }
}
