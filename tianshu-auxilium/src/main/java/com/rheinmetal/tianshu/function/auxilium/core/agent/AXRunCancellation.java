package com.rheinmetal.tianshu.function.auxilium.core.agent;

import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 运行期取消探针，对应《AX_Agent运行时设计.md》§3.1（每步之前重判）与 §3.3（取消是独立终态）。
 *
 * <p>取消原因沿用协议层的 {@link AXTurnCancellation}（内含 {@code DialogueReleaseReason}），
 * 不另造一套取消词汇：上层拿到终态后直接用这个原因释放会话。</p>
 *
 * <p>取消是异步到达的，所以循环在每一步之前重新询问，而不是只在入口判断一次。生产接线即
 * {@code AXRunCancellation.of(() -> execution.cancellationIfCancelled() != null,
 * execution::cancellationIfCancelled)}：谓词与原因读同一个一致的快照，不会读到"已取消但无原因"。</p>
 */
public interface AXRunCancellation {

    boolean cancelled();

    /** 取消原因；未取消时为 {@code null}。 */
    AXTurnCancellation reason();

    /** 永不取消的探针。 */
    static AXRunCancellation none() {
        return new AXRunCancellation() {
            @Override
            public boolean cancelled() {
                return false;
            }

            @Override
            public AXTurnCancellation reason() {
                return null;
            }
        };
    }

    /**
     * @param cancelled 是否已取消
     * @param reason    取消原因，仅在 {@code cancelled} 为真时有意义
     */
    static AXRunCancellation of(BooleanSupplier cancelled, Supplier<AXTurnCancellation> reason) {
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(reason, "reason");
        return new AXRunCancellation() {
            @Override
            public boolean cancelled() {
                return cancelled.getAsBoolean();
            }

            @Override
            public AXTurnCancellation reason() {
                return reason.get();
            }
        };
    }
}
