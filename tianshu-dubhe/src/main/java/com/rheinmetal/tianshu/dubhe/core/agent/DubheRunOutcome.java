package com.rheinmetal.tianshu.dubhe.core.agent;

import com.rheinmetal.tianshu.dubhe.DubheTurnCancellation;

/**
 * 一次 run 的收尾结果，对应《AX_Agent运行时设计.md》§3.3 与 §12.5。
 *
 * <p>非 {@link DubheRunTermination#COMPLETED} 的终态一律 {@code result == null}：循环不会凭空造一个
 * 结果出来。这就是设计 §4.1 的降级语义在运行期原语里的落点——调用方看到空结果必须换成资源键
 * 兜底句，不得把空结果当答案念出来（文案不在本类产生，一律走资源键）。</p>
 *
 * <p>{@code expiry} 必须与 {@code termination} 自洽：{@code ABORT_TIMEOUT} 一定要说明是哪个时钟
 * 到期；反过来，只有超时与取消允许非 {@code NONE} 的到期来源。违反即构造失败——这样
 * "三个时间窗谁先到"在类型层就分得开（§12.4）。</p>
 *
 * @param termination   终态
 * @param steps         实际完成的往返次数；抛出异常的那一步不计
 * @param elapsedMillis 本次 run 的挂钟
 * @param result        仅 {@link DubheRunTermination#COMPLETED} 时非空
 * @param cancellation  仅 {@link DubheRunTermination#CANCELLED} 时非空，取消原因（含释放原因）
 * @param failure       仅 {@link DubheRunTermination#FAILED} 时非空，那一步抛出的异常
 * @param expiry        这次结束由哪个时间窗造成（§12.4 的 ①②③），非超时类终态为 {@code NONE}
 * @param <T>           完成结果的类型
 */
public record DubheRunOutcome<T>(
        DubheRunTermination termination,
        int steps,
        long elapsedMillis,
        T result,
        DubheTurnCancellation cancellation,
        Throwable failure,
        DubheRunExpiry expiry
) {
    public DubheRunOutcome {
        if (termination == null) {
            throw new IllegalArgumentException("termination must not be null");
        }
        if (steps < 0) {
            throw new IllegalArgumentException("steps must be >= 0");
        }
        if (elapsedMillis < 0L) {
            throw new IllegalArgumentException("elapsedMillis must be >= 0");
        }
        if (expiry == null) {
            throw new IllegalArgumentException("expiry must not be null");
        }
        if (termination == DubheRunTermination.COMPLETED && result == null) {
            throw new IllegalArgumentException("completed run must carry a result");
        }
        if (termination == DubheRunTermination.CANCELLED && cancellation == null) {
            throw new IllegalArgumentException("cancelled run must carry a cancellation reason");
        }
        if (termination == DubheRunTermination.FAILED && failure == null) {
            throw new IllegalArgumentException("failed run must carry its failure");
        }
        if (termination == DubheRunTermination.ABORT_TIMEOUT && expiry == DubheRunExpiry.NONE) {
            throw new IllegalArgumentException("a timeout terminal must name the clock that expired");
        }
        boolean expiryCapable = termination == DubheRunTermination.ABORT_TIMEOUT
                || termination == DubheRunTermination.CANCELLED;
        if (!expiryCapable && expiry != DubheRunExpiry.NONE) {
            throw new IllegalArgumentException("only timeout and cancellation may report an expiry source");
        }
    }
}
