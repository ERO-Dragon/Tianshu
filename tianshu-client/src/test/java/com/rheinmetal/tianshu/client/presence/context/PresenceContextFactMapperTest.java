package com.rheinmetal.tianshu.client.presence.context;

import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceEquipment;
import com.rheinmetal.tianshu.client.presence.model.PresenceInventoryItem;
import com.rheinmetal.tianshu.client.presence.model.PresencePlayerStatus;
import com.rheinmetal.tianshu.client.presence.model.PresencePotionEffect;
import com.rheinmetal.tianshu.client.presence.model.PresenceTargetSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceWorldEnvironment;
import com.rheinmetal.tianshu.protocol.PresenceContextFactIds;
import com.rheinmetal.tianshu.protocol.payload.FactField;
import com.rheinmetal.tianshu.protocol.payload.FactValue;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresenceContextFactMapperTest {
    private final PresenceContextFactMapper mapper = new PresenceContextFactMapper();

    @Test
    void publishesOnlyTheRequestedGroups() {
        List<PresenceContextSnapshotPayload.FactPayload> facts =
                mapper.factsFrom(snapshot(), List.of(PresenceContextFactIds.PLAYER_STATUS));

        assertEquals(1, facts.size());
        assertEquals(PresenceContextFactIds.PLAYER_STATUS, facts.get(0).factId());
    }

    @Test
    void statusKeepsNumbersTypedAndCarriesTheDimensionName() {
        PresenceContextSnapshotPayload.FactPayload fact = onlyFact(PresenceContextFactIds.PLAYER_STATUS);

        assertEquals(new FactValue.Number(18.5D), fact.field("health").value());
        assertEquals(new FactValue.Count(7), fact.field("hunger").value());
        assertEquals(new FactValue.Identifier("minecraft:overworld"), fact.field("dimensionId").value());
        assertEquals(new FactValue.Text("主世界"), fact.field("dimensionDisplayName").value());
    }

    @Test
    void equipmentListsOccupiedSlotsOnlyAndKeepsTheArmourNumbers() {
        PresenceContextSnapshotPayload.FactPayload fact = onlyFact(PresenceContextFactIds.PLAYER_EQUIPMENT);

        FactValue.Record helmet = (FactValue.Record) fact.field("helmet").value();
        assertNull(fact.field("chestplate"), "an empty slot is the absence of the field");
        assertEquals(new FactValue.Identifier("minecraft:iron_helmet"), member(helmet, "itemId"));
        assertEquals(new FactValue.Count(1), member(helmet, "count"));
        assertEquals(new FactValue.Count(9), fact.field("armorPoints").value());
        assertEquals(new FactValue.Number(2.0D), fact.field("armorToughness").value());
    }

    @Test
    void inventoryIsOneTypedRecordPerItem() {
        PresenceContextSnapshotPayload.FactPayload fact = onlyFact(PresenceContextFactIds.PLAYER_INVENTORY);

        assertEquals(new FactValue.Count(2), fact.field("itemCount").value());
        List<FactValue.Record> items = fact.fields().stream()
                .filter(field -> "item".equals(field.name()))
                .map(field -> (FactValue.Record) field.value())
                .toList();
        assertEquals(2, items.size());
        assertEquals(new FactValue.Text("Bread"), member(items.get(0), "displayName"));
        assertEquals(new FactValue.Count(3), member(items.get(0), "count"));
    }

    @Test
    void interactionWithoutAnythingToInteractWithProducesNoFact() {
        PresenceContextSnapshot bare = new PresenceContextSnapshot(
                "player-uuid",
                "minecraft:overworld",
                "主世界",
                com.rheinmetal.tianshu.client.presence.model.PresenceScreenKind.NONE,
                "",
                "",
                List.of(),
                PresenceTargetSnapshot.empty(),
                false,
                false,
                false,
                PresencePlayerStatus.empty(),
                PresenceWorldEnvironment.empty(),
                List.of(),
                PresenceEquipment.empty(),
                List.of(),
                1L
        );

        assertTrue(mapper.factsFrom(bare, List.of(PresenceContextFactIds.INTERACTION_CONTEXT)).isEmpty());
    }

    @Test
    void interactionCarriesEquippedSlotsAsOneReadableList() {
        PresenceContextSnapshotPayload.FactPayload fact = onlyFact(PresenceContextFactIds.INTERACTION_CONTEXT);

        assertEquals(new FactValue.Text("minecraft:iron_helmet"), fact.field("equippedItemIds").value());
        assertEquals(new FactValue.Identifier("minecraft:iron_sword"), fact.field("heldItemId").value());
        assertEquals(new FactValue.Text("Iron Sword"), fact.field("heldItemDisplayName").value());
        assertEquals(new FactValue.Flag(true), fact.field("sneaking").value());
    }

    @Test
    void effectsWithoutANameProduceNoFact() {
        PresenceContextSnapshot snapshot = withEffects(List.of(new PresencePotionEffect("", "", 200, 0, true)));

        assertTrue(mapper.factsFrom(snapshot, List.of(PresenceContextFactIds.PLAYER_ACTIVE_EFFECTS)).isEmpty());
    }

    @Test
    void worldEnvironmentKeepsTheDayTimeAsACount() {
        PresenceContextSnapshotPayload.FactPayload fact = onlyFact(PresenceContextFactIds.WORLD_ENVIRONMENT);

        assertEquals(new FactValue.Identifier("minecraft:plains"), fact.field("biomeId").value());
        assertEquals(new FactValue.Count(6000), fact.field("dayTimeTicks").value());
        assertEquals(new FactValue.Flag(false), fact.field("raining").value());
    }

    private PresenceContextSnapshotPayload.FactPayload onlyFact(String factId) {
        List<PresenceContextSnapshotPayload.FactPayload> facts = mapper.factsFrom(snapshot(), List.of(factId));
        assertEquals(1, facts.size(), "expected exactly one fact for " + factId);
        return facts.get(0);
    }

    private static FactValue member(FactValue.Record record, String name) {
        return record.fields().stream()
                .filter(field -> name.equals(field.name()))
                .map(FactField::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing record member " + name));
    }

    private static PresenceContextSnapshot withEffects(List<PresencePotionEffect> effects) {
        return snapshot(effects);
    }

    private static PresenceContextSnapshot snapshot() {
        return snapshot(List.of(new PresencePotionEffect("minecraft:speed", "Speed", 200, 0, true)));
    }

    private static PresenceContextSnapshot snapshot(List<PresencePotionEffect> effects) {
        return new PresenceContextSnapshot(
                "player-uuid",
                "minecraft:overworld",
                "主世界",
                com.rheinmetal.tianshu.client.presence.model.PresenceScreenKind.NONE,
                "minecraft:iron_sword",
                "Iron Sword",
                List.of("minecraft:iron_helmet"),
                PresenceTargetSnapshot.empty(),
                true,
                false,
                true,
                new PresencePlayerStatus(18.5F, 20.0F, 7, 3),
                new PresenceWorldEnvironment(false, false, 6000L, "minecraft:plains", "Plains"),
                List.of(
                        new PresenceInventoryItem("minecraft:bread", "Bread", 3, 64),
                        new PresenceInventoryItem("minecraft:stick", "Stick", 2, 64)
                ),
                new PresenceEquipment(
                        new PresenceInventoryItem("minecraft:iron_helmet", "Iron Helmet", 1, 1),
                        null,
                        null,
                        null,
                        new PresenceInventoryItem("minecraft:iron_sword", "Iron Sword", 1, 1),
                        null,
                        9,
                        2.0F
                ),
                effects,
                1L
        );
    }
}
