package com.rheinmetal.tianshu.client.presence.status;

import com.rheinmetal.tianshu.client.presence.model.PresenceSeverity;
import com.rheinmetal.tianshu.client.presence.model.PresenceStatusSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceStatusType;
import com.rheinmetal.tianshu.platform.PresenceTextProvider;
import com.rheinmetal.tianshu.protocol.payload.AsrSpeechActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackState;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackStatusPayload;

import java.util.Map;

public final class PresenceDisplayPolicy {
    private static final long SHORT_TTL_MILLIS = 1_500L;
    private static final long ACTIVE_TTL_MILLIS = 8_000L;
    private static final long ERROR_TTL_MILLIS = 6_000L;
    private final PresenceTextProvider textProvider;

    public PresenceDisplayPolicy() {
        this(PresenceTextProvider.NOOP);
    }

    public PresenceDisplayPolicy(PresenceTextProvider textProvider) {
        this.textProvider = textProvider == null ? PresenceTextProvider.NOOP : textProvider;
    }

    public PresenceStatusSnapshot fromAsr(AsrSpeechActivityPayload payload) {
        if (payload == null || !payload.speaking()) {
            return status(PresenceStatusType.IDLE, PresenceSeverity.INFO, "module.asr", "presence.status.idle", SHORT_TTL_MILLIS, Map.of());
        }
        return status(PresenceStatusType.LISTENING, PresenceSeverity.INFO, "module.asr", "presence.status.listening", ACTIVE_TTL_MILLIS, Map.of(
                "sessionId", Long.toString(payload.sessionId())
        ));
    }

    public PresenceStatusSnapshot fromLlm(LlmStatusPayload payload) {
        if (payload == null) {
            return PresenceStatusSnapshot.idle();
        }
        return switch (payload.eventType()) {
            case LlmStatusPayload.QUEUED,
                    LlmStatusPayload.STARTED,
                    LlmStatusPayload.PREFILL_STARTED,
                    LlmStatusPayload.PREFILL_COMPLETED,
                    LlmStatusPayload.GENERATION_STARTED,
                    LlmStatusPayload.COLD_RESUME_STARTED -> status(
                    PresenceStatusType.THINKING,
                    PresenceSeverity.INFO,
                    "module.llm",
                    "presence.status.thinking",
                    ACTIVE_TTL_MILLIS,
                    llmAttributes(payload)
            );
            case LlmStatusPayload.FAILED -> status(
                    PresenceStatusType.ERROR,
                    PresenceSeverity.ERROR,
                    "module.llm",
                    "presence.status.error",
                    ERROR_TTL_MILLIS,
                    llmAttributes(payload)
            );
            case LlmStatusPayload.COMPLETED,
                    LlmStatusPayload.CANCELLED,
                    LlmStatusPayload.SUSPENDED,
                    LlmStatusPayload.COLD_RESUME_COMPLETED -> status(
                    PresenceStatusType.IDLE,
                    PresenceSeverity.INFO,
                    "module.llm",
                    "presence.status.idle",
                    SHORT_TTL_MILLIS,
                    llmAttributes(payload)
            );
            default -> PresenceStatusSnapshot.idle();
        };
    }

    public PresenceStatusSnapshot fromTts(TtsPlaybackStatusPayload payload) {
        if (payload == null || payload.state() == null || payload.state() == TtsPlaybackState.IDLE) {
            return status(PresenceStatusType.IDLE, PresenceSeverity.INFO, "module.tts", "presence.status.idle", SHORT_TTL_MILLIS, Map.of());
        }
        PresenceStatusType type = payload.state() == TtsPlaybackState.SPEAKING
                ? PresenceStatusType.SPEAKING
                : PresenceStatusType.THINKING;
        return status(type, PresenceSeverity.INFO, "module.tts", messageKey(type), ACTIVE_TTL_MILLIS, Map.of(
                "playbackState", payload.state().name()
        ));
    }

    public String displayText(PresenceStatusSnapshot snapshot) {
        PresenceStatusSnapshot effective = snapshot == null ? PresenceStatusSnapshot.idle() : snapshot;
        if (!effective.messageKey().isBlank() && textProvider.exists(effective.messageKey())) {
            return textProvider.text(effective.messageKey());
        }
        if (!effective.messageText().isBlank()) {
            return effective.messageText();
        }
        String fallbackKey = messageKey(effective.statusType());
        return textProvider.exists(fallbackKey) ? textProvider.text(fallbackKey) : "";
    }

    public PresenceHudDisplay hudDisplay(PresenceStatusSnapshot snapshot) {
        PresenceStatusSnapshot effective = snapshot == null ? PresenceStatusSnapshot.idle() : snapshot;
        String text = displayText(effective);
        if (text.isBlank()) {
            return PresenceHudDisplay.HIDDEN;
        }
        return new PresenceHudDisplay(true, text, effective.severity(), effective.statusType(), effective.sourceModuleId());
    }

    private PresenceStatusSnapshot status(
            PresenceStatusType type,
            PresenceSeverity severity,
            String sourceModuleId,
            String messageKey,
            long ttlMillis,
            Map<String, String> attributes
    ) {
        return new PresenceStatusSnapshot(type, severity, sourceModuleId, messageKey, "", System.currentTimeMillis(), ttlMillis, attributes);
    }

    private String messageKey(PresenceStatusType type) {
        return "presence.status." + type.name().toLowerCase(java.util.Locale.ROOT);
    }

    private Map<String, String> llmAttributes(LlmStatusPayload payload) {
        return Map.of(
                "taskId", payload.taskId(),
                "taskType", payload.taskType(),
                "eventType", payload.eventType()
        );
    }
}
