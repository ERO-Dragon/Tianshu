package com.rheinmetal.tianshu.function.auxilium.core.agent;

import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一步的"一次性信号槽"：回调负责 {@link #complete}，循环线程负责 {@link #await}。
 *
 * <p>这是 {@link AXAgentLoop} 的配套原语，<b>两种 Run 共用一份实现</b>：主对话的 Simple 步
 * （{@code core.turn}）与异步委派的 Complex 步（{@code core.delegate}）都只是"提交一次模型请求 +
 * 有界等待它的信号"，差别在策略参数而不在等待机制（设计 §3.1 / §5.2 / §12.2）。</p>
 *
 * <p>一次性语义：只有第一次 {@code complete} 生效。这样"provider 在 submit 里同步回灌"与"回灌晚于
 * 等待"两种时序都不会让循环跑偏，也是"回调先做完现有工作再 signal"这条纪律的落点——于是"循环返回
 * COMPLETED"就意味着调用方已经把该做的收尾做完了。</p>
 *
 * <p>唤醒是<b>事件优先、轮询兜底</b>：{@code complete} 立刻 {@code countDown}；50ms 只是"取消早于任何
 * 回调到达"时的兜底粒度。每一次靠轮询而非事件结束等待都记进 {@link #pollRescuedCount()}——正常应长期
 * 为 0，持续不为 0 就说明事件路径没生效（§12.3 表格最后一行 / §12.9 的观测要求）。</p>
 *
 * @param <T> 完成结果的类型
 */
public final class AXStepSignal<T> {

    private static final long POLL_INTERVAL_MILLIS = 50L;

    private final CountDownLatch arrived = new CountDownLatch(1);
    private final AtomicReference<AXStepOutcome<T>> outcome = new AtomicReference<>();
    private int pollRescuedCount;

    /** 幂等：只有第一次生效。{@code null} 被忽略（调用方不必在每条早退路径上判空）。 */
    public void complete(AXStepOutcome<T> signal) {
        if (signal == null) {
            return;
        }
        if (outcome.compareAndSet(null, signal)) {
            arrived.countDown();
        }
    }

    /**
     * 等到这一步有结论，或等到 {@code remainingMillis} 用完。
     *
     * @param remainingMillis 离循环截止时间的剩余量（由 {@link AXStepContext} 给出）
     * @param cancellation    取消探针；轮询发现取消时直接返回 {@link AXStepOutcome.Aborted}
     * @throws InterruptedException 等待被线程中断
     * @throws TimeoutException     {@code remainingMillis} 用尽；由循环按挂钟判 {@code ABORT_TIMEOUT}
     */
    public AXStepOutcome<T> await(long remainingMillis, AXRunCancellation cancellation)
            throws InterruptedException, TimeoutException {
        Objects.requireNonNull(cancellation, "cancellation");
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, remainingMillis));
        while (true) {
            AXStepOutcome<T> signal = outcome.get();
            if (signal != null) {
                return signal;
            }
            long leftNanos = deadlineNanos - System.nanoTime();
            if (leftNanos <= 0L) {
                throw new TimeoutException();
            }
            AXTurnCancellation reason = cancellation.reason();
            if (cancellation.cancelled() && reason != null) {
                // 事件路径没把取消送到（例如取消在请求提交之前就到了），是轮询救的场。
                pollRescuedCount++;
                return new AXStepOutcome.Aborted<>(reason, AXRunExpiry.NONE);
            }
            long sliceMillis = Math.min(POLL_INTERVAL_MILLIS, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(leftNanos)));
            arrived.await(sliceMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 本次一步中"轮询实际救场"的次数：{@code > 0} 表示取消不是靠 {@code complete} 唤醒的。
     *
     * <p>只在 {@link #await} 所在的循环线程上写入，run 返回后由提交它的线程读取，因此不是竞态字段。</p>
     */
    public int pollRescuedCount() {
        return pollRescuedCount;
    }
}
