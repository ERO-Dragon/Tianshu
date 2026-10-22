package com.rheinmetal.tianshu.function.auxilium.core.delegate;

/**
 * 把一个 Complex run 提交到后台线程执行（设计 §5.1 步骤 ④ / §3.2 的 {@code TASK} 车道）。
 *
 * <p>生产实现是 {@code AXProtocolAdapter.submitDelegateRun}：与主对话的 {@code :agent} <b>分开</b>的并发键，
 * 于是"一次 120s 的检索"不会把玩家下一句话堵在后面。测试注入 executor —— 不能用调用线程内联执行：回灌
 * 结果发生在测试线程上，内联会死锁。</p>
 */
@FunctionalInterface
public interface AXDelegateRunSubmitter {

    void submit(String taskId, Runnable task);
}
