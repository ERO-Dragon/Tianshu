package com.rheinmetal.tianshu.function.auxilium.core.agent;

import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 唯一的运行期循环实现，对应《AX_Agent运行时设计.md》§3.1：发请求 → 解释输出 → 继续或收尾。
 *
 * <p>两种运行不是两套代码：策略差异全部来自 {@link AXRunPolicy}，循环里没有"这是主对话还是
 * 异步委派"的分支。终止判定在每次请求<b>之前</b>按设计 §3.3 的顺序检查（步数安全阀 → 时长超限
 * → 取消），因此撞阀时不会再多发出一次请求。</p>
 *
 * <p>四路映射（§12.2）：{@link AXStepOutcome.Finished} → {@code COMPLETED}、
 * {@link AXStepOutcome.Aborted} → {@code CANCELLED}（并保留"谁到期"）、
 * {@link AXStepOutcome.Rejected} → {@code FAILED}、{@link AXStepOutcome.Continue} → 继续或撞
 * 无进展。**超时来源不许压平**：循环自己的截止时间记 {@link AXRunExpiry#LOOP_DEADLINE}，来自
 * step 的到期按它给的值原样带出（§12.4）。</p>
 *
 * <p>分层纪律：这里不出现玩家、播报、prompt 装配，也不引用任何 Minecraft 宿主类型；循环只认识
 * "一步"这个抽象（设计 §3.6 第 3 条）。生产接线见 §12：主对话把"一次 CHAT 请求 + 有界等待"
 * 实现成 {@link AXStepSource}，取消探针取
 * {@code AXRunCancellation.of(execution::cancellationIfCancelled, execution::cancellationIfCancelled)}。</p>
 */
public final class AXAgentLoop {

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final AXRunPolicy policy;
    private final LongSupplier nanoTime;

    public AXAgentLoop(AXRunPolicy policy) {
        this(policy, System::nanoTime);
    }

    /** 包内可见：让测试注入可控时钟（与 {@code AXLlmClient} 的时钟注入方式一致）。 */
    AXAgentLoop(AXRunPolicy policy, LongSupplier nanoTime) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * 跑一次运行直到终态。
     *
     * @param source       一步的实现；每次调用对应一次模型请求
     * @param cancellation 取消探针，可为 {@code null}（等同 {@link AXRunCancellation#none()}）
     */
    public <T> AXRunOutcome<T> run(AXStepSource<T> source, AXRunCancellation cancellation) {
        Objects.requireNonNull(source, "source");
        AXRunCancellation effectiveCancellation = cancellation == null ? AXRunCancellation.none() : cancellation;
        AXNoProgressDetector noProgress = policy.noProgressDetection() ? new AXNoProgressDetector() : null;
        long startedAtNanos = nanoTime.getAsLong();
        int steps = 0;

        while (true) {
            long elapsedMillis = elapsedMillisSince(startedAtNanos);

            if (steps >= policy.maxSteps()) {
                return terminal(AXRunTermination.ABORT_MAX_STEPS, steps, elapsedMillis, AXRunExpiry.NONE);
            }
            if (elapsedMillis >= policy.timeoutMillis()) {
                return terminal(AXRunTermination.ABORT_TIMEOUT, steps, elapsedMillis, AXRunExpiry.LOOP_DEADLINE);
            }
            if (effectiveCancellation.cancelled()) {
                AXTurnCancellation reason = effectiveCancellation.reason();
                if (reason == null) {
                    // 探针契约违约（cancelled 为真却无原因）：按失败处理，绝不编造取消原因。
                    return failed(new IllegalStateException("AX cancellation reason missing"),
                            steps, elapsedMillisSince(startedAtNanos));
                }
                return cancelled(reason, steps, elapsedMillis, AXRunExpiry.NONE);
            }

            AXStepOutcome<T> outcome;
            try {
                outcome = Objects.requireNonNull(
                        source.next(new AXStepContext(
                                steps,
                                elapsedMillis,
                                policy.timeoutMillis() - elapsedMillis,
                                policy,
                                effectiveCancellation
                        )),
                        "step outcome"
                );
            } catch (Exception failure) {
                long failedAtMillis = elapsedMillisSince(startedAtNanos);
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    AXTurnCancellation reason = effectiveCancellation.reason();
                    if (effectiveCancellation.cancelled() && reason != null) {
                        return cancelled(reason, steps, failedAtMillis, AXRunExpiry.NONE);
                    }
                    return failed(failure, steps, failedAtMillis);
                }
                // 一步自己撞到截止时间（有界等待超时）时，终态是"循环的时钟到期"，不是 FAILED。
                if (failedAtMillis >= policy.timeoutMillis()) {
                    return terminal(AXRunTermination.ABORT_TIMEOUT, steps, failedAtMillis, AXRunExpiry.LOOP_DEADLINE);
                }
                return failed(failure, steps, failedAtMillis);
            }

            steps++;
            if (outcome instanceof AXStepOutcome.Finished<?> finished) {
                T result = completedResult(finished);
                return new AXRunOutcome<>(
                        AXRunTermination.COMPLETED,
                        steps,
                        elapsedMillisSince(startedAtNanos),
                        result,
                        null,
                        null,
                        AXRunExpiry.NONE
                );
            }
            if (outcome instanceof AXStepOutcome.Aborted<?> aborted) {
                return cancelled(aborted.cancellation(), steps, elapsedMillisSince(startedAtNanos), aborted.expiry());
            }
            if (outcome instanceof AXStepOutcome.Rejected<?> rejected) {
                return failed(rejected.failure(), steps, elapsedMillisSince(startedAtNanos));
            }
            if (noProgress != null
                    && outcome instanceof AXStepOutcome.Continue<?> continuation
                    && noProgress.record(continuation)) {
                return terminal(AXRunTermination.ABORT_NO_PROGRESS, steps,
                        elapsedMillisSince(startedAtNanos), AXRunExpiry.NONE);
            }
        }
    }

    private static <T> AXRunOutcome<T> terminal(
            AXRunTermination termination, int steps, long elapsedMillis, AXRunExpiry expiry) {
        return new AXRunOutcome<>(termination, steps, elapsedMillis, null, null, null, expiry);
    }

    private static <T> AXRunOutcome<T> cancelled(
            AXTurnCancellation reason, int steps, long elapsedMillis, AXRunExpiry expiry) {
        return new AXRunOutcome<>(AXRunTermination.CANCELLED, steps, elapsedMillis, null, reason, null, expiry);
    }

    private static <T> AXRunOutcome<T> failed(Throwable failure, int steps, long elapsedMillis) {
        return new AXRunOutcome<>(AXRunTermination.FAILED, steps, elapsedMillis, null, null, failure, AXRunExpiry.NONE);
    }

    /**
     * 取出完成结果。
     *
     * <p>模式匹配拿不到泛型实参（Java 不支持 {@code instanceof Finished<T>}），而该结果只可能由
     * {@code AXStepSource<T>} 产出，因此这里的擦除转换是安全的。</p>
     */
    @SuppressWarnings("unchecked")
    private static <T> T completedResult(AXStepOutcome.Finished<?> finished) {
        return (T) finished.result();
    }

    private long elapsedMillisSince(long startedAtNanos) {
        long elapsedNanos = nanoTime.getAsLong() - startedAtNanos;
        return elapsedNanos <= 0L ? 0L : elapsedNanos / NANOS_PER_MILLI;
    }
}
