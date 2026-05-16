package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackPhase;

public record TtsRuntimeSnapshot(
        boolean bound,
        boolean running,
        boolean ready,
        boolean synthesisReady,
        boolean autoregressive,
        TtsPlaybackPhase playbackPhase,
        String activeRequestId,
        String activeSource,
        Priority activePriority,
        TtsFailureCode lastFailureCode,
        String lastFailureMessage,
        long updatedAtMillis
) {
    public TtsRuntimeSnapshot {
        playbackPhase = playbackPhase == null ? TtsPlaybackPhase.ACCEPTED : playbackPhase;
        activeRequestId = activeRequestId == null ? "" : activeRequestId.trim();
        activeSource = activeSource == null ? "" : activeSource.trim();
        activePriority = activePriority == null ? Priority.NORMAL : activePriority;
        lastFailureCode = lastFailureCode == null ? TtsFailureCode.UNKNOWN : lastFailureCode;
        lastFailureMessage = lastFailureMessage == null ? "" : lastFailureMessage.trim();
        updatedAtMillis = updatedAtMillis > 0L ? updatedAtMillis : System.currentTimeMillis();
    }

    public static TtsRuntimeSnapshot unbound() {
        return new TtsRuntimeSnapshot(false, false, false, false, false, TtsPlaybackPhase.ACCEPTED, "", "", Priority.NORMAL, TtsFailureCode.UNKNOWN, "", System.currentTimeMillis());
    }
}
