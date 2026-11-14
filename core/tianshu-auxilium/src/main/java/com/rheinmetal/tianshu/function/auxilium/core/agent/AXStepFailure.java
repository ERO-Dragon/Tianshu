package com.rheinmetal.tianshu.function.auxilium.core.agent;

/**
 * 一步的"预期内失败"载体：模型回了结果，但结果不是完成态。
 *
 * <p>它是<b>值</b>而不是控制流：一步通过 {@link AXStepOutcome.Rejected} 把它交回去，循环把它原样放进
 * {@code AXRunOutcome.failure()}，于是上层既能拿到原因码做诊断，也不会把这条预期内的分支塞进异常抛出
 * 路径（§3.1 "预期内的终止用返回值表达"）。两种 Run 共用一份：主对话的"结果非完成"与委派的"结论不可
 * 解析"是同一类失败，没有理由各造一个异常类。</p>
 */
public final class AXStepFailure extends RuntimeException {

    private final String reasonCode;

    public AXStepFailure(String reasonCode) {
        this(reasonCode, null);
    }

    public AXStepFailure(String reasonCode, Throwable cause) {
        super(reasonCode == null || reasonCode.isBlank() ? "step.failed" : reasonCode, cause);
        this.reasonCode = getMessage();
    }

    /** 稳定原因码（资源键风格，不是玩家文案），落诊断与事件线用。 */
    public String reasonCode() {
        return reasonCode;
    }
}
