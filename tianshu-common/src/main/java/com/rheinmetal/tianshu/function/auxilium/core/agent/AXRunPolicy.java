package com.rheinmetal.tianshu.function.auxilium.core.agent;

import com.rheinmetal.tianshu.protocol.CancellationScope;

/**
 * 一次 Agent 运行的策略参数（不可变值对象），对应《AX_Agent运行时设计.md》§3.2。
 *
 * <p>只有 {@link #simple()} 与 {@link #complex()} 两个预设，参数是闭集：调用方只能选预设，
 * 不得覆盖步数或时长上限（设计不变量 A8）。</p>
 *
 * <p>与 {@code AXRuntimePolicy} 的分工：后者是各协议客户端的 I/O 超时，本类是运行期循环的
 * 步数与挂钟上限——两处都叫"超时"，但一个是单次请求的，一个是整轮运行的。</p>
 *
 * <p>{@code cancellationScope} 沿用协议层 {@link CancellationScope} 的语义：一轮对话中断时
 * 只取消自己（{@link CancellationScope#SELF_ONLY}），异步委派中断时取消它派生的全部子请求
 * （{@link CancellationScope#CHILDREN}）。</p>
 *
 * @param id                  策略标识，落埋点用（不参与任何判定）
 * @param maxSteps            步数安全阀；1 步 = 1 次模型请求（设计 §3.1）
 * @param timeoutMillis       整轮挂钟上限
 * @param toolsAllowed        该策略下是否允许带 {@code tools[]}（主对话恒为否，设计 §4.2）
 * @param noProgressDetection 是否启用"连续相同一步即中止"（只有 Complex 启用，设计 §3.4）
 */
public record AXRunPolicy(
        String id,
        int maxSteps,
        long timeoutMillis,
        boolean toolsAllowed,
        boolean noProgressDetection,
        CancellationScope cancellationScope
) {
    public static final String SIMPLE_ID = "simple";
    public static final String COMPLEX_ID = "complex";

    /** Simple 步数安全阀：正常 1 步内终止，余量留给"输出不合契约"的修复（设计 §3.2）。 */
    public static final int SIMPLE_MAX_STEPS = 3;

    /** Simple 时长上限：撞到它说明卡住了，不是慢（设计 §3.2）。 */
    public static final long SIMPLE_TIMEOUT_MILLIS = 10_000L;

    /** Complex 步数安全阀（设计 §3.2）。 */
    public static final int COMPLEX_MAX_STEPS = 25;

    /** Complex 时长上限：与 03 文档的压缩超时取同值，TASK 车道上只该有一个挂钟数字。 */
    public static final long COMPLEX_TIMEOUT_MILLIS = 120_000L;

    public AXRunPolicy {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be >= 1");
        }
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        if (cancellationScope == null) {
            throw new IllegalArgumentException("cancellationScope must not be null");
        }
    }

    /**
     * 主对话与压缩用的预设：3 步 / 10 秒、不带工具、无无进展检测。
     *
     * <p>这是运行期公共原语，不是 AX 专属：任何模块只要需要"一次带结构化输出的模型调用加一个
     * 安全阀"，都用它（设计 §3.6）。</p>
     */
    public static AXRunPolicy simple() {
        return new AXRunPolicy(
                SIMPLE_ID,
                SIMPLE_MAX_STEPS,
                SIMPLE_TIMEOUT_MILLIS,
                false,
                false,
                CancellationScope.SELF_ONLY
        );
    }

    /**
     * 异步委派用的预设：25 步 / 120 秒、检索类工具、连续 3 步相同调用即中止。
     *
     * <p>只通过异步委派触发，因此它派生的子请求归它统一取消。</p>
     */
    public static AXRunPolicy complex() {
        return new AXRunPolicy(
                COMPLEX_ID,
                COMPLEX_MAX_STEPS,
                COMPLEX_TIMEOUT_MILLIS,
                true,
                true,
                CancellationScope.CHILDREN
        );
    }
}
