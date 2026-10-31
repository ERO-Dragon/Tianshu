package com.rheinmetal.tianshu.function.echo.runtime;

import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.function.echo.contract.TtsPlaybackState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsRuntimeSnapshotTest {
    @Test
    void unboundSnapshotUsesPublicDefaults() {
        TtsRuntimeSnapshot snapshot = TtsRuntimeSnapshot.unbound();

        assertFalse(snapshot.bound());
        assertFalse(snapshot.running());
        assertFalse(snapshot.ready());
        assertEquals(TtsPlaybackState.IDLE, snapshot.playbackState());
        assertEquals(Priority.NORMAL, snapshot.activePriority());
        assertEquals(TtsFailureCode.UNKNOWN, snapshot.lastFailureCode());
        assertEquals("", snapshot.activeRequestId());
        assertEquals("", snapshot.activeSource());
    }

    @Test
    void constructorNormalizesNullableValues() {
        TtsRuntimeSnapshot snapshot = new TtsRuntimeSnapshot(true, true, false, false, false, null, null, null, null, null, null, 0L);

        assertEquals(TtsPlaybackState.IDLE, snapshot.playbackState());
        assertEquals(Priority.NORMAL, snapshot.activePriority());
        assertEquals(TtsFailureCode.UNKNOWN, snapshot.lastFailureCode());
        assertEquals("", snapshot.lastFailureMessage());
    }

    @Test
    void previewingMatchesThePreviewRequestSourceRatherThanAnAbbreviation() {
        TtsRuntimeSnapshot preview = new TtsRuntimeSnapshot(
                true, true, true, true, false, TtsPlaybackState.SPEAKING,
                "request", TtsRequestSource.PREVIEW.value(), Priority.NORMAL, TtsFailureCode.UNKNOWN, "", 0L);
        TtsRuntimeSnapshot dialogue = new TtsRuntimeSnapshot(
                true, true, true, true, false, TtsPlaybackState.SPEAKING,
                "request", "module.ax", Priority.NORMAL, TtsFailureCode.UNKNOWN, "", 0L);

        assertTrue(preview.previewing());
        assertFalse(dialogue.previewing());
        assertFalse(TtsRuntimeSnapshot.unbound().previewing());
    }
}
