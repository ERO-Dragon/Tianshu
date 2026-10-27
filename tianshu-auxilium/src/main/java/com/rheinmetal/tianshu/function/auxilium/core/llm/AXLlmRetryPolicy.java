package com.rheinmetal.tianshu.function.auxilium.core.llm;

import com.rheinmetal.tianshu.function.auxilium.failure.AXFailureCause;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;

/**
 * 一次逻辑请求投几遍、下一遍之前等多久、哪些失败值得再投。
 *
 * <p>判定本身不在这里：成败归因属于全模块共用的那一份失败词表（{@link AXFailureCause}），
 * 设置页给玩家看的原因、账本里记的原因、以及这里的重投判定必须是同一个结论。本类只留两件纯算术的事：
 * 投几遍，以及每一遍之间等多久。</p>
 *
 * <p>"值不值得再投"因此只有一条规则：结果是失败，并且失败原因允许再来一次。取消不是失败，
 * 参数错误、上下文超限、内容拒绝再投也不会变，这四类由词表判定。</p>
 */
final class AXLlmRetryPolicy {
    /** 一次逻辑请求最多投递的遍数：首发一遍，重投四遍。 */
    static final int MAX_ATTEMPTS = 5;

    /**
     * 第 n 遍失败后、投第 n+1 遍之前等的毫秒数：曲线翻倍。
     *
     * <p>表长是 {@code MAX_ATTEMPTS - 1}，不是随手截断：第 {@value #MAX_ATTEMPTS} 遍就是最后一遍，
     * 它失败之后没有下一遍可等，所以没有 8 秒那一档。把 {@link #MAX_ATTEMPTS} 改成 6 时，
     * 这里必须同时补一档，否则第十五秒的等待无从表达。</p>
     */
    private static final long[] RETRY_DELAYS_MS = {500L, 1_000L, 2_000L, 4_000L};

    private AXLlmRetryPolicy() {
    }

    /**
     * 已经投出去 {@code attemptsMade} 遍之后，下一遍之前等多久。
     *
     * @param attemptsMade 已经投出去的遍数，至少为 1；超出退避表时取最后一档
     */
    static long delayMillis(int attemptsMade) {
        int index = Math.min(Math.max(attemptsMade, 1) - 1, RETRY_DELAYS_MS.length - 1);
        return RETRY_DELAYS_MS[index];
    }

    /** 这次失败值得再投一遍吗。取消与成功都不是失败，永远不重投。 */
    static boolean isRetryable(LLMPromptResultPayload result) {
        if (result == null || !result.isFailed()) {
            return false;
        }
        return causeOf(result).retryable();
    }

    /** 这次失败属于哪一类。玩家看到的原因与账本里记的原因都从这里取。 */
    static AXFailureCause causeOf(LLMPromptResultPayload result) {
        if (result == null) {
            return AXFailureCause.UNKNOWN;
        }
        return AXFailureCause.classify(result.errorCode(), result.errorMessage());
    }
}