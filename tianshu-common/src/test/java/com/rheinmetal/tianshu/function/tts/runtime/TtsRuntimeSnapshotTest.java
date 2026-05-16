package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackPhase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TtsRuntimeSnapshotTest {
    @Test
    void unboundSnapshotUsesPublicDefaults() {
        TtsRuntimeSnapshot snapshot = TtsRuntimeSnapshot.unbound();

        assertFalse(snapshot.bound());
        assertFalse(snapshot.running());
        assertFalse(snapshot.ready());
        assertEquals(TtsPlaybackPhase.ACCEPTED, snapshot.playbackPhase());
        assertEquals(Priority.NORMAL, snapshot.activePriority());
        assertEquals(TtsFailureCode.UNKNOWN, snapshot.lastFailureCode());
        assertEquals("", snapshot.activeRequestId());
        assertEquals("", snapshot.activeSource());
    }

    @Test
    void constructorNormalizesNullableValues() {
        TtsRuntimeSnapshot snapshot = new TtsRuntimeSnapshot(true, true, false, false, false, null, null, null, null, null, null, 0L);

        assertEquals(TtsPlaybackPhase.ACCEPTED, snapshot.playbackPhase());
        assertEquals(Priority.NORMAL, snapshot.activePriority());
        assertEquals(TtsFailureCode.UNKNOWN, snapshot.lastFailureCode());
        assertEquals("", snapshot.lastFailureMessage());
    }
}
