package com.rheinmetal.tianshu.function.auxilium.core.turn;

/**
 * 把一次 Agent run 提交到后台线程执行（《AX_Agent运行时设计.md》§12.1）。
 *
 * <p>生产实现是 {@code AXProtocolAdapter.submitAgentTurn}：协议 {@code ExecutionLane.LONG} 上
 * 的独立并发键，保证主对话的等待不占用协议 CPU 车道、也不与压缩任务互相排队。测试注入单线程
 * executor —— **不能用调用线程内联执行**：回调由测试线程稍后回灌，内联会死锁（§12.8）。</p>
 */
@FunctionalInterface
public interface AXAgentRunSubmitter {

    void submit(String taskId, Runnable task);
}
