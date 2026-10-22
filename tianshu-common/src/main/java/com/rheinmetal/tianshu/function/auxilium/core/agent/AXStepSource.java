package com.rheinmetal.tianshu.function.auxilium.core.agent;

/**
 * 一步的"发请求 → 解释输出"，对应《AX_Agent运行时设计.md》§3.1 循环体的第 ② ③ 步。
 *
 * <p>循环不关心请求怎么发：主对话是 CHAT 车道上一次不带 {@code tools[]} 的请求，Complex 是
 * TASK 车道上带检索工具的多次往返。循环只关心这一步是否终止。</p>
 *
 * <p>允许抛异常：循环把它收成 {@link AXRunTermination#FAILED} 终态，并保留原始异常，
 * 因此适配层不需要自己吞掉失败。</p>
 *
 * @param <T> 完成时可回传的结果类型
 */
@FunctionalInterface
public interface AXStepSource<T> {

    AXStepOutcome<T> next(AXStepContext context) throws Exception;
}
