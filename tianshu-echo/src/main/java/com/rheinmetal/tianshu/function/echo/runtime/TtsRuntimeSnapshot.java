package com.rheinmetal.tianshu.function.echo.runtime;

import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.function.echo.contract.TtsPlaybackState;

public record TtsRuntimeSnapshot(
        boolean bound,
        boolean running,
        boolean ready,
        boolean synthesisReady,
        boolean autoregressive,
        String activeModelName,
        TtsPlaybackState playbackState,
        String activeRequestId,
        String activeSource,
        Priority activePriority,
        TtsFailureCode lastFailureCode,
        String lastFailureMessage,
        long updatedAtMillis
) {
    public TtsRuntimeSnapshot {
        activeModelName = activeModelName == null ? "" : activeModelName.trim();
        playbackState = playbackState == null ? TtsPlaybackState.IDLE : playbackState;
        activeRequestId = activeRequestId == null ? "" : activeRequestId.trim();
        activeSource = activeSource == null ? "" : activeSource.trim();
        activePriority = activePriority == null ? Priority.NORMAL : activePriority;
        lastFailureCode = lastFailureCode == null ? TtsFailureCode.UNKNOWN : lastFailureCode;
        lastFailureMessage = lastFailureMessage == null ? "" : lastFailureMessage.trim();
        updatedAtMillis = updatedAtMillis > 0L ? updatedAtMillis : System.currentTimeMillis();
    }

    public static TtsRuntimeSnapshot unbound() {
        return new TtsRuntimeSnapshot(false, false, false, false, false, "", TtsPlaybackState.IDLE, "", "", Priority.NORMAL, TtsFailureCode.UNKNOWN, "", System.currentTimeMillis());
    }

    /** Whether the engine speaks with a different model than the one the configuration names. */
    public boolean modelMismatch(String configuredModelName) {
        String configured = configuredModelName == null ? "" : configuredModelName.trim();
        return !activeModelName.isEmpty() && !configured.isEmpty() && !activeModelName.equalsIgnoreCase(configured);
    }

    /** Whether the active playback belongs to a settings preview rather than to a dialogue turn. */
    public boolean previewing() {
        return TtsRequestSource.PREVIEW.value().equals(activeSource);
    }
}
