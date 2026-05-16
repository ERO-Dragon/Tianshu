package com.rheinmetal.tianshu.function.tts.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsBackendSnapshotTest {
    @Test
    void unavailableSnapshotUsesSafeDefaults() {
        TtsBackendSnapshot snapshot = TtsBackendSnapshot.unavailable();

        assertFalse(snapshot.resolved());
        assertFalse(snapshot.initialized());
        assertEquals(0, snapshot.sampleRate());
        assertEquals("", snapshot.engineType());
        assertEquals("", snapshot.modelDirectory());
        assertTrue(snapshot.updatedAtMillis() > 0L);
    }

    @Test
    void constructorNormalizesNullableValues() {
        TtsBackendSnapshot snapshot = new TtsBackendSnapshot(true, false, null, null, false, -1, null, 0L);

        assertEquals("", snapshot.engineType());
        assertEquals("", snapshot.modelDirectory());
        assertEquals(0, snapshot.sampleRate());
        assertTrue(snapshot.updatedAtMillis() > 0L);
    }
}
