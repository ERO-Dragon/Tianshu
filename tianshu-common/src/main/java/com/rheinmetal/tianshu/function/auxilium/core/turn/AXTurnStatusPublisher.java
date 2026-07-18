package com.rheinmetal.tianshu.function.auxilium.core.turn;

import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.protocol.status.ModuleStatusSeverity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;

public final class AXTurnStatusPublisher {
    public static final String TYPE_TURN_ACCEPTED = "turn.accepted";
    public static final String TYPE_TURN_PROCESSING = "turn.processing";
    public static final String TYPE_MEMORY_RETRIEVING = "turn.memory_retrieving";
    public static final String TYPE_LLM_THINKING = "turn.thinking";
    public static final String TYPE_RESPONDING = "turn.responding";
    public static final String TYPE_INTERRUPTED = "turn.interrupted";
    public static final String TYPE_FAILED = "turn.failed";
    public static final String TYPE_TURN_IDLE = "turn.idle";

    public static final String KEY_TURN_ACCEPTED = "tianshu.presence.module.ax.turn_accepted";
    public static final String KEY_TURN_PROCESSING = "tianshu.presence.module.ax.turn_processing";
    public static final String KEY_MEMORY_RETRIEVING = "tianshu.presence.module.ax.memory_retrieving";
    public static final String KEY_LLM_THINKING = "tianshu.presence.module.ax.thinking";
    public static final String KEY_RESPONDING = "tianshu.presence.module.ax.responding";
    public static final String KEY_INTERRUPTED = "tianshu.presence.module.ax.interrupted";
    public static final String KEY_FAILED = "tianshu.presence.module.ax.failed";
    public static final String KEY_TURN_IDLE = "tianshu.presence.module.ax.idle";

    private static final long SHORT_TTL_MILLIS = 1_500L;
    private static final long ACTIVE_TTL_MILLIS = 30_000L;
    private static final long FAILURE_TTL_MILLIS = 8_000L;

    private final AXProtocolAdapter adapter;

    public AXTurnStatusPublisher(AXProtocolAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    public void accepted() {
        publish(TYPE_TURN_ACCEPTED, KEY_TURN_ACCEPTED, ModuleStatusSeverity.INFO, SHORT_TTL_MILLIS, "received", "THINKING", null);
    }

    public void processing() {
        active("PROCESSING", true);
    }

    public void retrievingMemory() {
        active("MEMORY_RETRIEVING", true);
    }

    public void thinking() {
        active("THINKING", true);
    }

    public void responding() {
        active("RESPONDING", true);
    }

    public void active(String pipelineStage, boolean interruptible) {
        String normalizedStage = pipelineStage == null || pipelineStage.isBlank()
                ? "PROCESSING"
                : pipelineStage.trim().toUpperCase(java.util.Locale.ROOT);
        String presenceType = "RESPONDING".equals(normalizedStage) ? "SPEAKING" : "THINKING";
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
                presenceType,
                Map.of(
                        "axReplying", "true",
                        "axInterruptible", Boolean.toString(interruptible)
                )
        );
    }

    public void terminal(com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason reason) {
        publish(
                TYPE_TURN_IDLE,
                KEY_TURN_IDLE,
                ModuleStatusSeverity.INFO,
                SHORT_TTL_MILLIS,
                "TERMINAL",
                "IDLE",
                Map.of(
                        "axReplying", "false",
                        "axInterruptible", "false",
                        "releaseReason", reason == null ? "" : reason.name()
                )
        );
    }

    public void interrupted() {
        publish(TYPE_INTERRUPTED, KEY_INTERRUPTED, ModuleStatusSeverity.NOTICE, SHORT_TTL_MILLIS, "interrupted", "THINKING", null);
    }

    public void failed(String reasonCode) {
        Map<String, String> extraTags = sanitizeReason(reasonCode).isBlank()
                ? null
                : Map.of("reasonCode", sanitizeReason(reasonCode));
        publish(TYPE_FAILED, KEY_FAILED, ModuleStatusSeverity.CRITICAL, FAILURE_TTL_MILLIS, "failed", "ERROR", extraTags);
    }

    private void publish(
            String statusType,
            String messageKey,
            ModuleStatusSeverity severity,
            long ttlMillis,
            String pipelineStage,
            String presenceStatusType,
            Map<String, String> extraTags
    ) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("presenceStatusType", presenceStatusType);
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

    private String sanitizeReason(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return "";
        }
        String normalized = reasonCode.trim();
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }
}
