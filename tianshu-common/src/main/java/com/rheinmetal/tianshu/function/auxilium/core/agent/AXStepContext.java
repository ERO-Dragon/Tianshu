package com.rheinmetal.tianshu.function.auxilium.core.agent;

/**
 * 一步执行时交给 {@link AXStepSource} 的上下文，对应《AX_Agent运行时设计.md》§3.1。
 *
 * @param stepIndex       本轮之内的步序号，从 0 开始（1 步 = 1 次模型请求）
 * @param elapsedMillis   本次 run 已用挂钟
 * @param remainingMillis 距时长上限的剩余量，给下游请求设截止时间用；恒为正
 * @param policy          本次 run 的策略，是步数与时长上限的唯一来源
 * @param cancellation    取消探针：耗时的一步应轮询它，而不是假定自己不会被取消
 */
public record AXStepContext(
        int stepIndex,
        long elapsedMillis,
        long remainingMillis,
        AXRunPolicy policy,
        AXRunCancellation cancellation
) {
    public AXStepContext {
        if (stepIndex < 0) {
            throw new IllegalArgumentException("stepIndex must be >= 0");
        }
        if (elapsedMillis < 0L) {
            throw new IllegalArgumentException("elapsedMillis must be >= 0");
        }
        if (remainingMillis <= 0L) {
            throw new IllegalArgumentException("remainingMillis must be positive");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (cancellation == null) {
            throw new IllegalArgumentException("cancellation must not be null");
        }
    }

    public boolean cancelled() {
        return cancellation.cancelled();
    }
}
