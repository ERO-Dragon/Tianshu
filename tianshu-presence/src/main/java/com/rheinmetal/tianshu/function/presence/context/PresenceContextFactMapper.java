package com.rheinmetal.tianshu.function.presence.context;

import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceEquipment;
import com.rheinmetal.tianshu.client.presence.model.PresenceInventoryItem;
import com.rheinmetal.tianshu.client.presence.model.PresencePlayerStatus;
import com.rheinmetal.tianshu.client.presence.model.PresencePotionEffect;
import com.rheinmetal.tianshu.client.presence.model.PresenceScreenKind;
import com.rheinmetal.tianshu.client.presence.model.PresenceTargetSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceWorldEnvironment;
import com.rheinmetal.tianshu.protocol.PresenceContextFactIds;
import com.rheinmetal.tianshu.protocol.payload.FactField;
import com.rheinmetal.tianshu.protocol.payload.FactValue;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the requested parts of one capture into typed facts.
 *
 * <p>Presence publishes data, not sentences: every value keeps its type ({@code Identifier}, {@code Text},
 * {@code Number}, {@code Count}, {@code Flag}, {@code Record}), and a consumer that wants strings converts
 * them on its own side. A field that the capture did not carry is omitted rather than defaulted, so "absent"
 * stays distinguishable from "zero"; inside a record the members are always present, because a record is a
 * fixed shape its reader indexes by name.</p>
 *
 * <p>Nothing here renders, and nothing here serialises: the fact is built as the list of fields it will be
 * sent as.</p>
 */
public final class PresenceContextFactMapper {
    private static final long FACT_TTL_MILLIS = 120_000L;
    private static final int MAX_NAMED_EFFECTS = 6;

    public List<PresenceContextSnapshotPayload.FactPayload> factsFrom(
            PresenceContextSnapshot snapshot,
            List<String> requestedFactIds
    ) {
        if (snapshot == null || requestedFactIds == null || requestedFactIds.isEmpty()) {
            return List.of();
        }
        Set<String> requested = new LinkedHashSet<>();
        for (String factId : requestedFactIds) {
            if (factId != null && !factId.isBlank()) {
                requested.add(factId.trim());
            }
        }
        if (requested.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<PresenceContextSnapshotPayload.FactPayload> facts = new ArrayList<>();
        if (requested.contains(PresenceContextFactIds.INTERACTION_CONTEXT)) {
            add(facts, interactionContext(snapshot, now));
        }
        if (requested.contains(PresenceContextFactIds.PLAYER_STATUS)) {
            add(facts, playerStatus(snapshot, now));
        }
        if (requested.contains(PresenceContextFactIds.PLAYER_INVENTORY)) {
            add(facts, inventory(snapshot, now));
        }
        if (requested.contains(PresenceContextFactIds.PLAYER_EQUIPMENT)) {
            add(facts, playerEquipment(snapshot, now));
        }
        if (requested.contains(PresenceContextFactIds.PLAYER_ACTIVE_EFFECTS)) {
            add(facts, activeEffects(snapshot, now));
        }
        if (requested.contains(PresenceContextFactIds.WORLD_ENVIRONMENT)) {
            add(facts, worldEnvironment(snapshot, now));
        }
        return List.copyOf(facts);
    }

    private void add(List<PresenceContextSnapshotPayload.FactPayload> facts, PresenceContextSnapshotPayload.FactPayload fact) {
        if (fact != null && !fact.isEmpty()) {
            facts.add(fact);
        }
    }

    private PresenceContextSnapshotPayload.FactPayload playerStatus(PresenceContextSnapshot snapshot, long now) {
        PresencePlayerStatus status = snapshot.playerStatus();
        if (status == null || status.maxHealth() <= 0.0F) {
            return null;
        }
        List<FactField> fields = new ArrayList<>();
        fields.add(new FactField("health", new FactValue.Number(status.health())));
        fields.add(new FactField("maxHealth", new FactValue.Number(status.maxHealth())));
        fields.add(new FactField("hunger", new FactValue.Count(status.hunger())));
        fields.add(new FactField("experienceLevel", new FactValue.Count(status.experienceLevel())));
        addIdentifier(fields, "dimensionId", snapshot.dimensionId());
        addText(fields, "dimensionDisplayName", snapshot.dimensionDisplayName());
        return fact(PresenceContextFactIds.PLAYER_STATUS, 82, "player", List.of("player", "status"), now, fields);
    }

    /**
     * Worn and held items plus the armour numbers.
     *
     * <p>An empty slot contributes no field at all — "no chestplate" is the absence of the field, which is how
     * a reader tells it apart from a stack that happens to be empty. When nothing is worn, held or counted,
     * the whole fact is dropped: a fact that says nothing is worse than no fact.</p>
     */
    private PresenceContextSnapshotPayload.FactPayload playerEquipment(PresenceContextSnapshot snapshot, long now) {
        PresenceEquipment equipment = snapshot.equipment();
        if (equipment == null) {
            return null;
        }
        List<FactField> fields = new ArrayList<>();
        addEquipmentSlot(fields, "helmet", equipment.helmet());
        addEquipmentSlot(fields, "chestplate", equipment.chestplate());
        addEquipmentSlot(fields, "leggings", equipment.leggings());
        addEquipmentSlot(fields, "boots", equipment.boots());
        addEquipmentSlot(fields, "mainHand", equipment.mainHand());
        addEquipmentSlot(fields, "offHand", equipment.offHand());
        if (fields.isEmpty() && equipment.armorPoints() <= 0) {
            return null;
        }
        fields.add(new FactField("armorPoints", new FactValue.Count(equipment.armorPoints())));
        fields.add(new FactField("armorToughness", new FactValue.Number(equipment.armorToughness())));
        return fact(PresenceContextFactIds.PLAYER_EQUIPMENT, 84, "player",
                List.of("player", "equipment"), now, fields);
    }

    private void addEquipmentSlot(List<FactField> fields, String name, PresenceInventoryItem item) {
        if (item == null || item.count() <= 0) {
            return;
        }
        fields.add(new FactField(name, new FactValue.Record(List.of(
                new FactField("itemId", new FactValue.Identifier(item.itemId())),
                new FactField("displayName", new FactValue.Text(item.displayName())),
                new FactField("count", new FactValue.Count(item.count())),
                new FactField("maxStackSize", new FactValue.Count(item.maxStackSize()))
        ))));
    }

    private PresenceContextSnapshotPayload.FactPayload worldEnvironment(PresenceContextSnapshot snapshot, long now) {
        PresenceWorldEnvironment environment = snapshot.worldEnvironment();
        if (environment == null || environment.biomeId().isBlank()) {
            return null;
        }
        List<FactField> fields = new ArrayList<>();
        addIdentifier(fields, "dimensionId", snapshot.dimensionId());
        addText(fields, "dimensionDisplayName", snapshot.dimensionDisplayName());
        addIdentifier(fields, "biomeId", environment.biomeId());
        addText(fields, "biomeDisplayName", environment.biomeDisplayName());
        fields.add(new FactField("raining", new FactValue.Flag(environment.raining())));
        fields.add(new FactField("thundering", new FactValue.Flag(environment.thundering())));
        fields.add(new FactField("dayTimeTicks", new FactValue.Count((int) environment.dayTimeTicks())));
        return fact(PresenceContextFactIds.WORLD_ENVIRONMENT, 66, "world", List.of("world", "environment"), now, fields);
    }

    private PresenceContextSnapshotPayload.FactPayload inventory(PresenceContextSnapshot snapshot, long now) {
        if (snapshot.inventoryItems().isEmpty()) {
            return null;
        }
        List<FactField> fields = new ArrayList<>();
        fields.add(new FactField("itemCount", new FactValue.Count(snapshot.inventoryItems().size())));
        for (PresenceInventoryItem item : snapshot.inventoryItems()) {
            if (item == null || item.count() <= 0) {
                continue;
            }
            fields.add(new FactField("item", new FactValue.Record(List.of(
                    new FactField("itemId", new FactValue.Identifier(item.itemId())),
                    new FactField("displayName", new FactValue.Text(item.displayName())),
                    new FactField("count", new FactValue.Count(item.count())),
                    new FactField("maxStackSize", new FactValue.Count(item.maxStackSize()))
            ))));
        }
        return fact(PresenceContextFactIds.PLAYER_INVENTORY, 78, "player_inventory",
                List.of("inventory", "items"), now, fields);
    }

    private PresenceContextSnapshotPayload.FactPayload activeEffects(PresenceContextSnapshot snapshot, long now) {
        if (snapshot.activeEffects().isEmpty()) {
            return null;
        }
        // The emptiness rule is "no effect with anything to say", phrased on the effects themselves rather
        // than on a rendered sentence, so it does not depend on wording existing.
        boolean named = snapshot.activeEffects().stream()
                .filter(effect -> effect != null && readableName(effect))
                .limit(MAX_NAMED_EFFECTS)
                .findAny()
                .isPresent();
        if (!named) {
            return null;
        }
        List<FactField> fields = new ArrayList<>();
        for (PresencePotionEffect effect : snapshot.activeEffects()) {
            if (effect == null || effect.effectId().isBlank()) {
                continue;
            }
            fields.add(new FactField("effect", new FactValue.Record(List.of(
                    new FactField("effectId", new FactValue.Identifier(effect.effectId())),
                    new FactField("displayName", new FactValue.Text(effect.displayName())),
                    new FactField("durationTicks", new FactValue.Count(effect.durationTicks())),
                    new FactField("amplifier", new FactValue.Count(effect.amplifier()))
            ))));
        }
        return fact(PresenceContextFactIds.PLAYER_ACTIVE_EFFECTS, 76, "player", List.of("player", "effects"), now, fields);
    }

    /**
     * What the player is looking at, holding and doing.
     *
     * <p>Two fields are always written ({@code playerId}, {@code dimensionId}); anything beyond them is real
     * interaction data, and without it the fact is dropped. {@code equippedItemIds} stays the only carrier of
     * "which slots hold something", because arbitration reads it as a list.</p>
     */
    private PresenceContextSnapshotPayload.FactPayload interactionContext(PresenceContextSnapshot snapshot, long now) {
        List<FactField> fields = new ArrayList<>();
        addIdentifier(fields, "playerId", snapshot.playerId());
        addIdentifier(fields, "dimensionId", snapshot.dimensionId());
        addText(fields, "dimensionDisplayName", snapshot.dimensionDisplayName());
        int identityFieldCount = fields.size();
        if (!snapshot.heldItemId().isBlank()) {
            addIdentifier(fields, "heldItemId", snapshot.heldItemId());
            addText(fields, "heldItemDisplayName", snapshot.heldItemDisplayName());
        }
        if (snapshot.screenKind() != PresenceScreenKind.NONE) {
            addIdentifier(fields, "screenKind", snapshot.screenKind().name());
        }
        if (snapshot.interactionKeyDown()) {
            fields.add(new FactField("interactionKeyDown", new FactValue.Flag(true)));
        }
        if (snapshot.attackKeyDown()) {
            fields.add(new FactField("attackKeyDown", new FactValue.Flag(true)));
        }
        if (snapshot.sneaking()) {
            fields.add(new FactField("sneaking", new FactValue.Flag(true)));
        }
        PresenceTargetSnapshot target = snapshot.crosshairTarget();
        if (target != null && target.present()) {
            addIdentifier(fields, "crosshairTargetId", target.entityId());
            addIdentifier(fields, "crosshairTargetTypeId", target.entityTypeId());
            addText(fields, "crosshairTargetDisplayName", target.displayName());
            fields.add(new FactField("crosshairTargetDistance", new FactValue.Number(target.distance())));
        }
        boolean hasInteraction = fields.size() > identityFieldCount
                || (snapshot.equippedItemIds() != null && !snapshot.equippedItemIds().isEmpty());
        if (!hasInteraction) {
            return null;
        }
        return fact(PresenceContextFactIds.INTERACTION_CONTEXT, 84, "interaction", List.of("interaction", "screen"),
                now, equip(fields, snapshot.equippedItemIds()));
    }

    private List<FactField> equip(List<FactField> fields, List<String> equippedItemIds) {
        if (equippedItemIds == null || equippedItemIds.isEmpty()) {
            return fields;
        }
        List<FactField> withEquipped = new ArrayList<>(fields);
        withEquipped.add(new FactField("equippedItemIds", new FactValue.Text(String.join("|", equippedItemIds))));
        return withEquipped;
    }

    private void addIdentifier(List<FactField> fields, String name, String value) {
        if (value != null && !value.isBlank()) {
            fields.add(new FactField(name, new FactValue.Identifier(value)));
        }
    }

    private void addText(List<FactField> fields, String name, String value) {
        if (value != null && !value.isBlank()) {
            fields.add(new FactField(name, new FactValue.Text(value)));
        }
    }

    /** Whether an effect has anything a reader could name; the id is the fallback name. */
    private boolean readableName(PresencePotionEffect effect) {
        return !effect.displayName().isBlank() || !effect.effectId().isBlank();
    }

    private PresenceContextSnapshotPayload.FactPayload fact(
            String factId,
            int priority,
            String subject,
            List<String> tags,
            long now,
            List<FactField> fields
    ) {
        return new PresenceContextSnapshotPayload.FactPayload(
                factId,
                fields,
                priority,
                "presence",
                subject,
                tags,
                now,
                FACT_TTL_MILLIS
        );
    }
}
