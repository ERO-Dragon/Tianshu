package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackPhase;

public final class TtsPlaybackStatusMapper {
    private TtsPlaybackStatusMapper() {
    }

    public static TtsPlaybackPhase phaseOf(TtsSession session) {
        if (session == null || session.state() == null) {
            return TtsPlaybackPhase.ACCEPTED;
        }
        return switch (session.state()) {
            case CREATED, QUEUED -> TtsPlaybackPhase.ACCEPTED;
            case SYNTHESIZING -> TtsPlaybackPhase.STARTED;
            case PLAYING -> TtsPlaybackPhase.SPEAKING;
            case DRAINING -> TtsPlaybackPhase.DRAINING;
            case COMPLETED -> TtsPlaybackPhase.COMPLETED;
            case CANCELLED -> TtsPlaybackPhase.CANCELLED;
            case FAILED -> TtsPlaybackPhase.FAILED;
        };
    }

    public static String publicMessage(TtsSession session) {
        if (session == null) {
            return "";
        }
        return switch (phaseOf(session)) {
            case CANCELLED -> "cancelled";
            case FAILED -> "failed";
            default -> "";
        };
    }
}
