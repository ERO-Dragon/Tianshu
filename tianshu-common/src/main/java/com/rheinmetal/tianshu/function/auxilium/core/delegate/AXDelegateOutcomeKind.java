package com.rheinmetal.tianshu.function.auxilium.core.delegate;

import com.rheinmetal.tianshu.function.auxilium.core.agent.AXRunTermination;

/**
 * 一次委派的终态分类与它的播报资源键（设计 §5.3 状态机 / §6.3 事件线 {@code kind} / §6.2 措辞纪律）。
 *
 * <p>§6.3 只允许四个 {@code kind}；{@code ABORT_MAX_STEPS} / {@code ABORT_NO_PROGRESS} 这类"没查全"的
 * 收尾按 {@code delegate_failed} 投递（§5.2 步骤 ⑥：仍按成功路径投递，只是语义不同）。</p>
 *
 * <p>播放文案一律走资源键：本枚举只持有键名，句子在语音切片的资源目录里，<b>代码里不出现自然语言</b>。</p>
 */
public enum AXDelegateOutcomeKind {
    /** 拿到结论：播报读结论正文本身，不再二次改写（§6.2）。 */
    OK("delegate_ok", null),
    /** 失败或"没查全"：必须出声一句（§6.2）。 */
    FAILED("delegate_failed", "ax.runtime.delegate.failed"),
    /** 撞 COMPLEX 的 120s 挂钟（§3.2 / A.1）。 */
    TIMEOUT("delegate_timeout", "ax.runtime.delegate.timeout"),
    /** 本地状态机取消（§5.3；不是模型工具，§7.4）。 */
    CANCELLED("delegate_cancelled", "ax.runtime.delegate.cancelled");

    /** 委派回执"我查一下"的播报键：与结论播报是两条不同资源键，不得复用（§6.2）。 */
    public static final String ACK_SPEECH_KEY = "ax.runtime.delegate.ack";

    private final String code;
    private final String speechKey;

    AXDelegateOutcomeKind(String code, String speechKey) {
        this.code = code;
        this.speechKey = speechKey;
    }

    /** 事件线 {@code kind}（裸名，§6.3）。 */
    public String code() {
        return code;
    }

    /** 该终态的播报资源键；{@link #OK} 用结论正文，因此为 {@code null}。 */
    public String speechKey() {
        return speechKey;
    }

    /** 只有拿到结论的一类不需要兜底句。 */
    public boolean mustSpeakFallback() {
        return speechKey != null;
    }

    /** 终态到分类的唯一映射处（不要在调用方各写一份 switch）。 */
    public static AXDelegateOutcomeKind of(AXRunTermination termination) {
        if (termination == null) {
            return FAILED;
        }
        return switch (termination) {
            case COMPLETED -> OK;
            case ABORT_TIMEOUT -> TIMEOUT;
            case CANCELLED -> CANCELLED;
            case ABORT_MAX_STEPS, ABORT_NO_PROGRESS, FAILED -> FAILED;
        };
    }
}
