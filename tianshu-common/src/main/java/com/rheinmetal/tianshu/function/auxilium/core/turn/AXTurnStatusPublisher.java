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

    public static final String KEY_TURN_ACCEPTED = "tianshu.presence.module.ax.turn_accepted";
    public static final String KEY_TURN_PROCESSING = "tianshu.presence.module.ax.turn_processing";
    public static final String KEY_MEMORY_RETRIEVING = "tianshu.presence.module.ax.memory_retrieving";
    public static final String KEY_LLM_THINKING = "tianshu.presence.module.ax.thinking";
    public static final String KEY_RESPONDING = "tianshu.presence.module.ax.responding";
    public static final String KEY_INTERRUPTED = "tianshu.presence.module.ax.interrupted";
    public static final String KEY_FAILED = "tianshu.presence.module.ax.failed";

    private static final long SHORT_TTL_MILLIS = 1_500L;
    private static final long ACTIVE_TTL_MILLIS = 4_000L;
    private static final long FAILURE_TTL_MILLIS = 8_000L;

    private final AXProtocolAdapter adapter;

    public AXTurnStatusPublisher(AXProtocolAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    public void accepted() {
        publish(TYPE_TURN_ACCEPTED, KEY_TURN_ACCEPTED, ModuleStatusSeverity.INFO, SHORT_TTL_MILLIS, "received", "THINKING", null);
    }

    public void processing() {
        publish(TYPE_TURN_PROCESSING, KEY_TURN_PROCESSING, ModuleStatusSeverity.INFO, ACTIVE_TTL_MILLIS, "processing", "THINKING", null);
    }

    public void retrievingMemory() {
        publish(TYPE_MEMORY_RETRIEVING, KEY_MEMORY_RETRIEVING, ModuleStatusSeverity.INFO, ACTIVE_TTL_MILLIS, "memory_retrieving", "THINKING", null);
    }

    public void thinking() {
        publish(TYPE_LLM_THINKING, KEY_LLM_THINKING, ModuleStatusSeverity.INFO, ACTIVE_TTL_MILLIS, "llm_thinking", "THINKING", null);
    }

    public void responding() {
        publish(TYPE_RESPONDING, KEY_RESPONDING, ModuleStatusSeverity.INFO, ACTIVE_TTL_MILLIS, "responding", "SPEAKING", null);
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
        tags.put("axPipelineStage", pipelineStage);
        if (extraTags != null) {
            tags.putAll(extraTags);
        }
        adapter.publishModuleStatus(ModuleStatus.keyed(
                AXProtocolAdapter.MODULE_ID,
                statusType,
                messageKey,
                "",
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
