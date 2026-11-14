package com.rheinmetal.tianshu.function.auxilium.core.turn;

import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.protocol.status.ModuleStatusSeverity;
import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.failure.AXFailureCause;

public final class AXTurnStatusPublisher {
    public static final String TYPE_TURN_ACCEPTED = "turn.accepted";
    public static final String TYPE_TURN_PROCESSING = "turn.processing";
    public static final String TYPE_MEMORY_RETRIEVING = "turn.memory_retrieving";
    public static final String TYPE_LLM_THINKING = "turn.thinking";
    public static final String TYPE_RESPONDING = "turn.responding";
    public static final String TYPE_INTERRUPTED = "turn.interrupted";
    public static final String TYPE_FAILED = "turn.failed";
    public static final String TYPE_TURN_IDLE = "turn.idle";

    public static final String KEY_TURN_ACCEPTED = "tianshu.runtime.module.ax.turn_accepted";
    public static final String KEY_TURN_PROCESSING = "tianshu.runtime.module.ax.turn_processing";
    public static final String KEY_MEMORY_RETRIEVING = "tianshu.runtime.module.ax.memory_retrieving";
    public static final String KEY_LLM_THINKING = "tianshu.runtime.module.ax.thinking";
    public static final String KEY_RESPONDING = "tianshu.runtime.module.ax.responding";
    public static final String KEY_INTERRUPTED = "tianshu.runtime.module.ax.interrupted";
    /** 失败状态的语言键；认不出原因时就是这一句。词表是唯一出处，这里不留第二份字面量。 */
    public static final String KEY_FAILED = "tianshu.runtime.module.ax.failed";
    public static final String KEY_TURN_IDLE = "tianshu.runtime.module.ax.idle";

    private static final long SHORT_TTL_MILLIS = 1_500L;
    private static final long ACTIVE_TTL_MILLIS = 30_000L;
    private static final long FAILURE_TTL_MILLIS = 8_000L;

    private final AXProtocolAdapter adapter;
    private volatile FailureObserver failureObserver = FailureObserver.NONE;

    /**
     * 一轮的结局只在这里对外发生一次：失败，或者办成。
     *
     * <p>失败账本挂在这两个点上，而不是去猜轮次状态——轮次有没有办成，状态发布器本来就最先知道。
     * 观察者不许抛异常：一次记账失败不能让一轮对话跟着失败。</p>
     */
    public interface FailureObserver {
        FailureObserver NONE = new FailureObserver() {
        };

        /** 这一轮失败了。原因已经归好类，观察者不必再解析机器码。 */
        default void onTurnFailed(AXTurnExecution execution, AXFailureCause cause, String reasonCode) {
        }

        /** 这一轮被中断（玩家打断、过期、换代）。中断不是失败：它可能已经答出了一部分。 */
        default void onTurnInterrupted(AXTurnExecution execution) {
        }

        /** 这一轮真的答出来了：账本里同轮次的待办该撤了。 */
        default void onTurnResolved(AXTurnExecution execution) {
        }
    }

    public AXTurnStatusPublisher(AXProtocolAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    public void accepted(AXTurnExecution execution) {
        publish(TYPE_TURN_ACCEPTED, KEY_TURN_ACCEPTED, ModuleStatusSeverity.INFO, SHORT_TTL_MILLIS, "received", null);
        transition(execution, PresenceActivityType.THINKING);
    }

    public void active(AXTurnExecution execution, String pipelineStage, boolean interruptible) {
        String normalizedStage = pipelineStage == null || pipelineStage.isBlank()
                ? "PROCESSING"
                : pipelineStage.trim().toUpperCase(java.util.Locale.ROOT);
        String statusType = switch (normalizedStage) {
            case "MEMORY_RETRIEVING" -> TYPE_MEMORY_RETRIEVING;
            case "THINKING" -> TYPE_LLM_THINKING;
            case "RESPONDING" -> TYPE_RESPONDING;
            default -> TYPE_TURN_PROCESSING;
        };
        String messageKey = switch (normalizedStage) {
            case "MEMORY_RETRIEVING" -> KEY_MEMORY_RETRIEVING;
            case "THINKING" -> KEY_LLM_THINKING;
            case "RESPONDING" -> KEY_RESPONDING;
            default -> KEY_TURN_PROCESSING;
        };
        publish(
                statusType,
                messageKey,
                ModuleStatusSeverity.INFO,
                ACTIVE_TTL_MILLIS,
                normalizedStage,
                Map.of(
                        "axReplying", "true",
                        "axInterruptible", Boolean.toString(interruptible)
                )
        );
        transition(
                execution,
                "RESPONDING".equals(normalizedStage)
                        ? PresenceActivityType.RESPONDING
                        : PresenceActivityType.THINKING
        );
    }

    public void terminal(AXTurnExecution execution, com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason reason) {
        publish(
                TYPE_TURN_IDLE,
                KEY_TURN_IDLE,
                ModuleStatusSeverity.INFO,
                SHORT_TTL_MILLIS,
                "TERMINAL",
                Map.of(
                        "axReplying", "false",
                        "axInterruptible", "false",
                        "releaseReason", reason == null ? "" : reason.name()
                )
        );
        transition(execution, null);
        // 这一轮办成了：账本里同轮次的待办到此为止。只在"确实答出来了"时撤账——
        // 中断、淘汰、过期都不是办成，那些轮次该留在账本里等玩家决定。
        if (reason == com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason.OWNER_COMPLETED) {
            FailureObserver current = failureObserver;
            if (current != null) {
                current.onTurnResolved(execution);
            }
        }
    }

    public void interrupted() {
        interrupted(null);
    }

    /** 中断了某一轮：状态照旧，日志也知道这是哪一轮。 */
    public void interrupted(AXTurnExecution execution) {
        publish(TYPE_INTERRUPTED, KEY_INTERRUPTED, ModuleStatusSeverity.NOTICE, SHORT_TTL_MILLIS, "interrupted", null);
        FailureObserver current = failureObserver;
        if (current != null && execution != null) {
            try {
                current.onTurnInterrupted(execution);
            } catch (RuntimeException ignored) {
                // 观察者是旁路：它坏了，玩家该看到的中断提示仍然要出去。
            }
        }
    }

    public void failed(String reasonCode) {
        failed(reasonCode, AXFailureCause.UNKNOWN);
    }

    public void observeFailures(FailureObserver observer) {
        this.failureObserver = observer == null ? FailureObserver.NONE : observer;
    }

    /** 失败了，而且知道是哪一轮、哪一类失败：状态与账本都从这里走。 */
    public void failed(AXTurnExecution execution, String reasonCode, AXFailureCause cause) {
        failed(reasonCode, cause);
        FailureObserver current = failureObserver;
        if (current != null && execution != null) {
            try {
                current.onTurnFailed(execution, cause == null ? AXFailureCause.UNKNOWN : cause, reasonCode);
            } catch (RuntimeException ignored) {
                // 记账是本轮的旁路：它坏了，玩家该看到的那句失败提示仍然要出去。
            }
        }
    }

    /**
     * 失败了，而且知道是哪一类失败。
     *
     * <p>玩家看到的那句话按失败类别选：把"服务商这会儿不行"和"你的密钥不对"说成同一句"AX 处理失败"，
     * 等于让玩家自己去猜该重试还是该改设置。原始机器码仍然作为标签带着，那是给诊断看的，不进正文。</p>
     */
    public void failed(String reasonCode, AXFailureCause cause) {
        AXFailureCause effective = cause == null ? AXFailureCause.UNKNOWN : cause;
        Map<String, String> extraTags = new LinkedHashMap<>();
        extraTags.put("reasonCode", sanitizeReason(reasonCode));
        extraTags.put("failureCause", effective.code());
        publish(TYPE_FAILED, effective.messageKey(), ModuleStatusSeverity.CRITICAL, FAILURE_TTL_MILLIS, "failed", extraTags);
    }

    private void publish(
            String statusType,
            String messageKey,
            ModuleStatusSeverity severity,
            long ttlMillis,
            String pipelineStage,
            Map<String, String> extraTags
    ) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("axPipelineStage", pipelineStage == null ? "" : pipelineStage.toLowerCase(java.util.Locale.ROOT));
        if (extraTags != null) {
            tags.putAll(extraTags);
        }
        adapter.publishModuleStatus(ModuleStatus.keyed(
                AXProtocolAdapter.MODULE_ID,
                statusType,
                messageKey,
                severity,
                ttlMillis,
                tags
        ));
    }

    private void transition(AXTurnExecution execution, PresenceActivityType next) {
        if (execution == null) {
            return;
        }
        PresenceActivityType previous = execution.swapPresenceActivity(next);
        if (previous == next) {
            return;
        }
        if (previous != null) {
            adapter.publishChatActivity(execution.activityId(), previous, false);
        }
        if (next != null) {
            adapter.publishChatActivity(execution.activityId(), next, true);
        }
    }

    private String sanitizeReason(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return "";
        }
        String normalized = reasonCode.trim();
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }
}
