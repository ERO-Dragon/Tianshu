package com.rheinmetal.tianshu.client.host.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The value shapes P1 added; no game classpath needed. */
class ClientGameSnapshotRecordsTest {

    @Test
    void blockTargetKeepsPresenceDistinctFromAnUnnamedBlock() {
        assertTrue(GameBlockTarget.EMPTY.blockId().isEmpty());
        assertEquals(false, GameBlockTarget.EMPTY.present());

        GameBlockTarget named = new GameBlockTarget(" minecraft:stone ", " Stone ", 1, 64, 2, true);
        assertEquals("minecraft:stone", named.blockId());
        assertEquals("Stone", named.blockDisplayName());
    }

    @Test
    void poseTrimsTheDimensionAndMode() {
        GamePlayerPose pose = new GamePlayerPose(" minecraft:overworld ", 1.0D, 2.0D, 3.0D, 90.0F, 0.0F, " survival ");
        assertEquals("minecraft:overworld", pose.dimensionId());
        assertEquals("survival", pose.gameMode());
    }

    @Test
    void vitalsClampExperienceToTheUnitRange() {
        GamePlayerVitals vitals = new GamePlayerVitals(1.7F, -1.0F, -2.0F);
        assertEquals(1.0F, vitals.experienceProgress());
        assertEquals(0.0F, vitals.saturation());
        assertEquals(0.0F, vitals.absorption());
    }

    @Test
    void containerViewCopiesItsEntries() {
        GameContainerView view = new GameContainerView(" Chest ", List.of(
                new GameContainerEntry(-1, " minecraft:stone ", " Stone ", -4)));
        assertEquals("Chest", view.title());
        assertEquals(1, view.entries().size());
        assertEquals(0, view.entries().get(0).slot());
        assertEquals("minecraft:stone", view.entries().get(0).itemId());
        assertEquals(0, view.entries().get(0).count());
        assertThrows(UnsupportedOperationException.class, () -> view.entries().clear());
    }

    @Test
    void playerListEntryKeepsOnlyPublicFields() {
        GamePlayerListEntry entry = new GamePlayerListEntry(" Alice ", -5, " creative ");
        assertEquals("Alice", entry.name());
        assertEquals(0, entry.latencyMillis());
        assertEquals("creative", entry.gameMode());
    }
}
