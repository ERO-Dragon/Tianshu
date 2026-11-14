package com.rheinmetal.tianshu.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ModelAvailabilitySnapshotTest {
    @Test
    void snapshotIsImmutableAndMissingEntriesRemainUnknown() {
        ModelAvailabilitySnapshot snapshot = new ModelAvailabilitySnapshot(
                Map.of("model", new ModelAvailabilitySnapshot.Entry(true, 42L)),
                true,
                10L
        );

        assertTrue(snapshot.ready());
        assertTrue(snapshot.entry("model").installed());
        assertEquals(42L, snapshot.entry("model").sizeBytes());
        assertFalse(snapshot.entries().containsKey("missing"));
        assertEquals(0L, new ModelAvailabilitySnapshot.Entry(false, -1L).sizeBytes());
    }
}
