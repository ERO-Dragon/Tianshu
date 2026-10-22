package com.rheinmetal.tianshu.function.auxilium.core.agent;

/**
 * 一次 Agent 运行的终态，对应《AX_Agent运行时设计.md》§3.3 的终止条件。
 *
 * <p>判定优先级固定为：显式完成 → 无进展 → 步数安全阀 → 时长超限，另有取消与内部失败两条
 * 外部来源。终态一旦写出不再改写（设计 §3.3）：要重答就新建一次 run，不回改旧结果。</p>
 */
public enum AXRunTermination {
    /** 显式完成：拿到了结构化结果（设计 §3.2 终止条件 ①）。 */
    COMPLETED,

    /** 撞步数安全阀。Simple 与 Complex 都可能，差的是上限值。 */
    ABORT_MAX_STEPS,

    /** 连续 3 步相同工具加相同参数，判定无进展（只有 Complex 会启用，设计 §3.4）。 */
    ABORT_NO_PROGRESS,

    /** 撞时长上限。 */
    ABORT_TIMEOUT,

    /** 外部取消：玩家再次说话、模块卸载、会话过期等。 */
    CANCELLED,

    /** 一步抛出了异常。 */
    FAILED
}
