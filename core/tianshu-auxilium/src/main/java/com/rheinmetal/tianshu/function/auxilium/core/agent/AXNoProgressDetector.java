package com.rheinmetal.tianshu.function.auxilium.core.agent;

import java.util.Objects;

/**
 * 连续相同一步的检测器，对应《AX_Agent运行时设计.md》§3.4。
 *
 * <p>只在一个 run 内有效，由 {@link AXAgentLoop} 每次 run 新建，因此不需要重置、也不会跨任务
 * 串味。</p>
 *
 * <p>比较的是 {@link AXStepOutcome.Continue} 的（工具名，规范化参数）两元组本身。刻意不用相似度：
 * 换个关键词再查一次是正常重试，参数不同就不该被判无进展；而"一模一样再来一遍三次"只可能是
 * 工具返回空或模型没看懂，两种都要人看。</p>
 */
final class AXNoProgressDetector {

    /** 设计 §3.4：连续 3 步相同即中止。 */
    static final int STREAK_LIMIT = 3;

    private AXStepOutcome.Continue<?> last;
    private int streak;

    /**
     * @return {@code true} 表示已连续 {@link #STREAK_LIMIT} 步相同，应当中止
     */
    boolean record(AXStepOutcome.Continue<?> step) {
        Objects.requireNonNull(step, "step");
        if (step.equals(last)) {
            streak++;
        } else {
            last = step;
            streak = 1;
        }
        return streak >= STREAK_LIMIT;
    }
}
