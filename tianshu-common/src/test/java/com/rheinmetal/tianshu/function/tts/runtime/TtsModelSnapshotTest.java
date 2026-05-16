package com.rheinmetal.tianshu.function.tts.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsModelSnapshotTest {
    @Test
    void unconfiguredSnapshotUsesSafeDefaults() {
        TtsModelSnapshot snapshot = TtsModelSnapshot.unconfigured();

        assertFalse(snapshot.configured());
        assertFalse(snapshot.catalogMatched());
        assertFalse(snapshot.directoryExists());
        assertEquals("", snapshot.modelName());
        assertEquals("", snapshot.modelDirectory());
        assertTrue(snapshot.updatedAtMillis() > 0L);
    }

    @Test
    void constructorNormalizesNullableValues() {
        TtsModelSnapshot snapshot = new TtsModelSnapshot(true, false, false, false, null, null, null, null, null, null, false, false, false, null, 0L);

        assertEquals("", snapshot.modelName());
        assertEquals("", snapshot.displayName());
        assertEquals("", snapshot.modelId());
        assertEquals("", snapshot.engineType());
        assertEquals("", snapshot.modelDirectory());
        assertTrue(snapshot.updatedAtMillis() > 0L);
    }
}
