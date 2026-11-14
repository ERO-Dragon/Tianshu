package com.rheinmetal.tianshu.function.auxilium.core.agent;

import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;

/**
 * 一步的产出，对应《AX_Agent运行时设计.md》§3.1 循环体的第 ③ 步，以及 §12.2 的四路映射。
 *
 * <p>四种形态覆盖全部情形，循环因此不需要知道"这一步做了什么"：工具、检索、契约修复在它眼里
 * 都只是"还没完"。**预期内的终止用返回值表达，异常只留给真正的意外**——这样
 * {@code AXRunOutcome.failure()} 里出现的东西就一定是 bug，而不是"正常失败被包成异常"。</p>
 *
 * @param <T> 完成时可回传的结果类型
 */
public sealed interface AXStepOutcome<T> {

    /** 显式完成（设计 §3.2 终止条件 ①）。 */
    record Finished<T>(T result) implements AXStepOutcome<T> {
        public Finished {
            if (result == null) {
                throw new IllegalArgumentException("result must not be null");
            }
        }
    }

    /**
     * 还需要一步——这是本片预留的多步扩展点（设计 §3.2 的"多步 + 工具"）。
     *
     * <p>主对话（Simple、不带 {@code tools[]}）在生产路径上只会产出 {@link Finished}；
     * Simple 上出现本形态只有一种正当来由：输出不合契约时的重问（设计 §4.1）。</p>
     *
     * <p>两个分量相等即视为"相同的工具加相同的参数"，供 Complex 的无进展检测比对（设计 §3.4）。
     * 参数规范化（key 排序、NFC 归一、去行尾空白，即 03 文档的 D1/D2/D6）由产出这一侧的适配层
     * 完成，检测器只做相等比较，不做相似度判断。</p>
     *
     * @param toolName           工具名；无工具的继续（如契约重问）用稳定的固定标识
     * @param canonicalArguments 已规范化的参数文本；无参数的工具传空串
     */
    record Continue<T>(String toolName, String canonicalArguments) implements AXStepOutcome<T> {
        public Continue {
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("toolName must not be blank");
            }
            if (canonicalArguments == null) {
                throw new IllegalArgumentException("canonicalArguments must not be null");
            }
        }
    }

    /**
     * 这一步被外部中断：取消，或某个时间窗到期（设计 §12.3 / §12.4）。
     *
     * <p>{@code expiry} 是"谁到期"的唯一记录点：普通取消记 {@link AXRunExpiry#NONE}，
     * provider 信封过期记 {@link AXRunExpiry#LLM_ENVELOPE}，IA 交付租约到期记
     * {@link AXRunExpiry#SESSION_LEASE}。循环把它原样带进 {@code AXRunOutcome}，不许压平。</p>
     */
    record Aborted<T>(AXTurnCancellation cancellation, AXRunExpiry expiry) implements AXStepOutcome<T> {
        public Aborted {
            if (cancellation == null) {
                throw new IllegalArgumentException("cancellation must not be null");
            }
            if (expiry == null) {
                throw new IllegalArgumentException("expiry must not be null");
            }
        }
    }

    /**
     * 这一步明确失败但没有抛异常（典型：provider 回了非完成的结果）。
     *
     * <p>刻意用返回值而不是异常：失败是**预期内**的分支，把预期分支塞进异常会让
     * {@code AXRunOutcome.failure()} 失去"这是 bug"的语义。真正的意外（提交失败、装配抛错）
     * 仍然由 step 直接抛出，循环原样保留。</p>
     */
    record Rejected<T>(Throwable failure) implements AXStepOutcome<T> {
        public Rejected {
            if (failure == null) {
                throw new IllegalArgumentException("failure must not be null");
            }
        }
    }
}
