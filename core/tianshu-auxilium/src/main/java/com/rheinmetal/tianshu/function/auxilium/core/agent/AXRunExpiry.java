package com.rheinmetal.tianshu.function.auxilium.core.agent;

/**
 * 一次 run 因"哪个时间窗到期"而结束，对应《AX_Agent运行时设计.md》§12.4。
 *
 * <p>三种来源必须分开记：它们指向完全不同的责任方——循环自己的预算、provider 的信封、
 * IA 的交付租约。都归成一个"超时"就分不清是谁的问题了（§12.5 的验收要求）。</p>
 */
public enum AXRunExpiry {
    /** 不是超时终态。 */
    NONE,
    /** ① {@code AXRunPolicy} 自己的截止时间（Simple 10s / Complex 120s）。 */
    LOOP_DEADLINE,
    /** ② provider 信封过期（{@code AXLlmClient} 自身判定，与 AX 的截止时间无关）。 */
    LLM_ENVELOPE,
    /** ③ IA 交付租约到期（外部要求本 turn 过期）。 */
    SESSION_LEASE
}
