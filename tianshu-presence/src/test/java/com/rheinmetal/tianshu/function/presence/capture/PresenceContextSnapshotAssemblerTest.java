package com.rheinmetal.tianshu.function.presence.capture;

import com.rheinmetal.tianshu.client.host.game.ClientGameContextPort;
import com.rheinmetal.tianshu.client.host.game.GameBlockTarget;
import com.rheinmetal.tianshu.client.host.game.GameContainerEntry;
import com.rheinmetal.tianshu.client.host.game.GameContainerView;
import com.rheinmetal.tianshu.client.host.game.GameEffect;
import com.rheinmetal.tianshu.client.host.game.GameEquipment;
import com.rheinmetal.tianshu.client.host.game.GameInteractionContext;
import com.rheinmetal.tianshu.client.host.game.GameInventoryEntry;
import com.rheinmetal.tianshu.client.host.game.GamePlayerListEntry;
import com.rheinmetal.tianshu.client.host.game.GamePlayerPose;
import com.rheinmetal.tianshu.client.host.game.GamePlayerStatus;
import com.rheinmetal.tianshu.client.host.game.GamePlayerVitals;
import com.rheinmetal.tianshu.client.host.game.GameScreenKind;
import com.rheinmetal.tianshu.client.host.game.GameTarget;
import com.rheinmetal.tianshu.client.host.game.GameWorldEnvironment;
import com.rheinmetal.tianshu.function.presence.context.PresenceContextGroup;
import com.rheinmetal.tianshu.function.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.function.presence.model.PresenceEquipment;
import com.rheinmetal.tianshu.function.presence.model.PresenceInventoryItem;
import com.rheinmetal.tianshu.function.presence.model.PresencePlayerStatus;
import com.rheinmetal.tianshu.function.presence.model.PresencePotionEffect;
import com.rheinmetal.tianshu.function.presence.model.PresenceScreenKind;
import com.rheinmetal.tianshu.function.presence.model.PresenceTargetSnapshot;
import com.rheinmetal.tianshu.function.presence.model.PresenceWorldEnvironment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the game-information path presence had inside the kernel.
 *
 * <p>Before the extraction the platform returned a finished {@code PresenceContextSnapshot}; now it returns neutral
 * api values and this module maps them. The mapping is the whole "does presence still see the game correctly"
 * question, so every field is checked against a sentinel value here, together with the two older guarantees: a
 * request reads only the groups it asked for, and an absent world answers empty instead of throwing.</p>
 */
final class PresenceContextSnapshotAssemblerTest {

    private final PresenceContextSnapshotAssembler assembler = new PresenceContextSnapshotAssembler();

    @Test
    void everyGroupIsMappedIntoTheSnapshot() {
        RecordingPort port = new RecordingPort();

        PresenceContextSnapshot snapshot = assembler.assemble(port, EnumSet.allOf(PresenceContextGroup.class));

        assertEquals("player-1", snapshot.playerId());
        assertEquals("minecraft:overworld", snapshot.dimensionId());
        assertEquals("Overworld", snapshot.dimensionDisplayName());
        assertEquals(PresenceScreenKind.CONTAINER, snapshot.screenKind());
        assertEquals("minecraft:diamond_sword", snapshot.heldItemId());
        assertEquals("Diamond Sword", snapshot.heldItemDisplayName());
        assertEquals(List.of("minecraft:diamond_sword", "minecraft:shield"), snapshot.equippedItemIds());
        assertTrue(snapshot.interactionKeyDown());
        assertTrue(snapshot.attackKeyDown());
        assertTrue(snapshot.sneaking());
        assertTarget(snapshot.crosshairTarget());
        assertPlayerStatus(snapshot.playerStatus());
        assertEnvironment(snapshot.worldEnvironment());
        assertInventory(snapshot.inventoryItems());
        assertEquals(new PresenceEquipment(
                        item("minecraft:iron_helmet", "Iron Helmet", 1),
                        null,
                        item("minecraft:iron_leggings", "Iron Leggings", 1),
                        null,
                        item("minecraft:diamond_sword", "Diamond Sword", 1),
                        item("minecraft:shield", "Shield", 1),
                        7,
                        2.0F
                ), snapshot.equipment());
        assertEffects(snapshot.activeEffects());
        assertEquals(1234L, snapshot.capturedAtMillis());
    }

    @Test
    void oneRequestReadsOnlyTheGroupsItAskedFor() {
        RecordingPort port = new RecordingPort();

        PresenceContextSnapshot snapshot = assembler.assemble(port, Set.of(PresenceContextGroup.PLAYER_STATUS));

        assertEquals(Set.of("playerId", "capturePlayerStatus"), Set.copyOf(port.calls));
        assertEquals("", snapshot.dimensionId());
        assertEquals("", snapshot.heldItemId());
        assertEquals(PresenceScreenKind.NONE, snapshot.screenKind());
        assertTrue(snapshot.equippedItemIds().isEmpty());
        assertTrue(snapshot.inventoryItems().isEmpty());
        assertEquals(PresenceEquipment.empty(), snapshot.equipment());
        assertTrue(snapshot.activeEffects().isEmpty());
        assertEquals(PresenceWorldEnvironment.empty(), snapshot.worldEnvironment());
        assertPlayerStatus(snapshot.playerStatus());
    }

    @Test
    void noRequestedGroupDefaultsToTheInteractionSlice() {
        RecordingPort port = new RecordingPort();

        assembler.assemble(port, null);
        assertEquals(Set.of("playerId", "captureInteraction"), Set.copyOf(port.calls));

        port.calls.clear();
        assembler.assemble(port, Set.of());
        assertEquals(Set.of("playerId", "captureInteraction"), Set.copyOf(port.calls));
    }

    @Test
    void aMissingWorldAnswersEmptyInsteadOfThrowing() {
        RecordingPort port = new RecordingPort();
        port.empty = true;

        PresenceContextSnapshot snapshot = assembler.assemble(port, EnumSet.allOf(PresenceContextGroup.class));

        assertEquals("", snapshot.playerId());
        assertEquals("", snapshot.dimensionId());
        assertEquals("", snapshot.dimensionDisplayName());
        assertEquals(PresenceScreenKind.NONE, snapshot.screenKind());
        assertEquals("", snapshot.heldItemId());
        assertTrue(snapshot.equippedItemIds().isEmpty());
        assertTrue(snapshot.inventoryItems().isEmpty());
        assertEquals(PresenceEquipment.empty(), snapshot.equipment());
        assertTrue(snapshot.activeEffects().isEmpty());
        assertEquals(PresenceWorldEnvironment.empty(), snapshot.worldEnvironment());
        assertEquals(PresenceTargetSnapshot.empty(), snapshot.crosshairTarget());
        assertTrue(snapshot.capturedAtMillis() > 0L, "an empty capture still carries its capture time");
    }

    private static void assertTarget(PresenceTargetSnapshot target) {
        assertTrue(target.present());
        assertEquals("entity-1", target.entityId());
        assertEquals("minecraft:zombie", target.entityTypeId());
        assertEquals("Zombie", target.displayName());
        assertEquals(3.5D, target.distance());
    }

    private static void assertPlayerStatus(PresencePlayerStatus status) {
        assertEquals(18.0F, status.health());
        assertEquals(20.0F, status.maxHealth());
        assertEquals(15, status.hunger());
        assertEquals(6, status.experienceLevel());
    }

    private static void assertEnvironment(PresenceWorldEnvironment environment) {
        assertTrue(environment.raining());
        assertTrue(environment.thundering());
        assertEquals(9000L, environment.dayTimeTicks());
        assertEquals("minecraft:plains", environment.biomeId());
        assertEquals("Plains", environment.biomeDisplayName());
    }

    private static void assertInventory(List<PresenceInventoryItem> items) {
        assertEquals(1, items.size());
        assertEquals("minecraft:cobblestone", items.get(0).itemId());
        assertEquals("Cobblestone", items.get(0).displayName());
        assertEquals(32, items.get(0).count());
        assertEquals(64, items.get(0).maxStackSize());
    }

    private static void assertEffects(List<PresencePotionEffect> effects) {
        assertEquals(1, effects.size());
        assertEquals("minecraft:speed", effects.get(0).effectId());
        assertEquals("Speed", effects.get(0).displayName());
        assertEquals(200, effects.get(0).durationTicks());
        assertEquals(1, effects.get(0).amplifier());
        assertTrue(effects.get(0).beneficial());
    }

    private static PresenceInventoryItem item(String itemId, String displayName, int count) {
        return new PresenceInventoryItem(itemId, displayName, count, 1);
    }

    private static final class RecordingPort implements ClientGameContextPort {
        private final List<String> calls = new ArrayList<>();
        private boolean empty;

        @Override
        public String playerId() {
            calls.add("playerId");
            return empty ? "" : "player-1";
        }

        @Override
        public GameInteractionContext captureInteraction() {
            calls.add("captureInteraction");
            if (empty) {
                return GameInteractionContext.empty();
            }
            return new GameInteractionContext(
                    "minecraft:overworld",
                    "Overworld",
                    GameScreenKind.CONTAINER,
                    "minecraft:diamond_sword",
                    "Diamond Sword",
                    List.of("minecraft:diamond_sword", "minecraft:shield"),
                    new GameTarget("entity-1", "minecraft:zombie", "Zombie", 3.5D, true),
                    true,
                    true,
                    true,
                    1234L
            );
        }

        @Override
        public GamePlayerStatus capturePlayerStatus() {
            calls.add("capturePlayerStatus");
            return empty ? GamePlayerStatus.EMPTY : new GamePlayerStatus(18.0F, 20.0F, 15, 6);
        }

        @Override
        public List<GameInventoryEntry> captureInventory() {
            calls.add("captureInventory");
            return empty ? List.of() : List.of(new GameInventoryEntry("minecraft:cobblestone", "Cobblestone", 32, 64));
        }

        @Override
        public GameEquipment captureEquipment() {
            calls.add("captureEquipment");
            if (empty) {
                return GameEquipment.EMPTY;
            }
            return new GameEquipment(
                    new GameInventoryEntry("minecraft:iron_helmet", "Iron Helmet", 1, 1),
                    null,
                    new GameInventoryEntry("minecraft:iron_leggings", "Iron Leggings", 1, 1),
                    null,
                    new GameInventoryEntry("minecraft:diamond_sword", "Diamond Sword", 1, 1),
                    new GameInventoryEntry("minecraft:shield", "Shield", 1, 1),
                    7,
                    2.0F
            );
        }

        @Override
        public List<GameEffect> captureEffects() {
            calls.add("captureEffects");
            return empty ? List.of() : List.of(new GameEffect("minecraft:speed", "Speed", 200, 1, true));
        }

        @Override
        public GameWorldEnvironment captureEnvironment() {
            calls.add("captureEnvironment");
            return empty
                    ? GameWorldEnvironment.EMPTY
                    : new GameWorldEnvironment(true, true, 9000L, "minecraft:plains", "Plains");
        }

        @Override
        public GameBlockTarget captureBlockTarget() {
            calls.add("captureBlockTarget");
            return empty
                    ? GameBlockTarget.EMPTY
                    : new GameBlockTarget("minecraft:stone", "Stone", 1, 64, 1, true);
        }

        @Override
        public GamePlayerPose capturePlayerPose() {
            calls.add("capturePlayerPose");
            return empty
                    ? GamePlayerPose.EMPTY
                    : new GamePlayerPose("minecraft:overworld", 1.0D, 64.0D, 1.0D, 90.0F, 0.0F, "survival");
        }

        @Override
        public GamePlayerVitals captureVitals() {
            calls.add("captureVitals");
            return empty ? GamePlayerVitals.EMPTY : new GamePlayerVitals(0.5F, 3.0F, 0.0F);
        }

        @Override
        public GameContainerView captureOpenContainer() {
            calls.add("captureOpenContainer");
            return empty
                    ? GameContainerView.EMPTY
                    : new GameContainerView("Chest", List.of(
                            new GameContainerEntry(0, "minecraft:stone", "Stone", 1)));
        }

        @Override
        public List<GamePlayerListEntry> capturePlayerList() {
            calls.add("capturePlayerList");
            return empty ? List.of() : List.of(new GamePlayerListEntry("player-1", 20, "survival"));
        }
    }
}
