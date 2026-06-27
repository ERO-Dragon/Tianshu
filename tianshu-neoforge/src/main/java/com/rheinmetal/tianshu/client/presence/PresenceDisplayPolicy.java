package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.protocol.payload.AsrSpeechActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackState;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackStatusPayload;
import net.minecraft.client.resources.language.I18n;

import java.util.Map;

public final class PresenceDisplayPolicy {
    private static final long SHORT_TTL_MILLIS = 1_500L;
    private static final long ACTIVE_TTL_MILLIS = 8_000L;
    private static final long ERROR_TTL_MILLIS = 6_000L;

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
        if (!effective.messageKey().isBlank() && I18n.exists(effective.messageKey())) {
            return I18n.get(effective.messageKey());
        }
        if (!effective.messageText().isBlank()) {
            return effective.messageText();
        }
        String fallbackKey = messageKey(effective.statusType());
        return I18n.exists(fallbackKey) ? I18n.get(fallbackKey) : "";
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
