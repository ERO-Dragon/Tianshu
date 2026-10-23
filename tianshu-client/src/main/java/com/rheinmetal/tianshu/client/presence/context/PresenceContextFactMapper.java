package com.rheinmetal.tianshu.client.presence.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class PresenceContextFactMapper {
    private static final long FACT_TTL_MILLIS = 120_000L;

    public List<PresenceContextSnapshotPayload.FactPayload> factsFrom(
            PresenceContextSnapshot snapshot,
            List<String> requestedFactIds
    ) {
        if (snapshot == null || requestedFactIds == null || requestedFactIds.isEmpty()) {
            return List.of();
        }
        Set<String> requested = requestedFactIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requested.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<PresenceContextSnapshotPayload.FactPayload> facts = new ArrayList<>();
        if (requested.contains(PresenceContextFactIds.INTERACTION_CONTEXT)) {
            addFact(facts, interactionContext(snapshot, now));
        }
        if (requested.contains(PresenceContextFactIds.PLAYER_STATUS)) {
            addFact(facts, playerStatus(snapshot, now));
        }
        if (requested.contains(PresenceContextFactIds.PLAYER_INVENTORY)) {
            addFact(facts, inventory(snapshot, now));
        }
        if (requested.contains(PresenceContextFactIds.PLAYER_EQUIPMENT)) {
            addFact(facts, playerEquipment(snapshot, now));
        }
        if (requested.contains(PresenceContextFactIds.PLAYER_ACTIVE_EFFECTS)) {
            addFact(facts, activeEffects(snapshot, now));
        }
        if (requested.contains(PresenceContextFactIds.WORLD_ENVIRONMENT)) {
            addFact(facts, worldEnvironment(snapshot, now));
        }
        return List.copyOf(facts);
    }

    private void addFact(List<PresenceContextSnapshotPayload.FactPayload> facts, PresenceContextSnapshotPayload.FactPayload fact) {
        if (fact != null && !fact.isEmpty()) {
            facts.add(fact);
        }
    }

    private PresenceContextSnapshotPayload.FactPayload playerStatus(PresenceContextSnapshot snapshot, long now) {
        PresencePlayerStatus status = snapshot.playerStatus();
        if (status == null || status.maxHealth() <= 0.0F) {
            return null;
        }
        Map<String, String> values = orderedMap();
        values.put("health", formatDecimal(status.health()));
        values.put("maxHealth", formatDecimal(status.maxHealth()));
        values.put("hunger", Integer.toString(status.hunger()));
        values.put("experienceLevel", Integer.toString(status.experienceLevel()));
        values.put("dimensionId", snapshot.dimensionId());
        // No rendered sentence: presence publishes data, and the consumers that need words are the ones that
        // own the wording (AX renders from the typed fields, in its own language).
        return fact(PresenceContextFactIds.PLAYER_STATUS, 82, "player", List.of("player", "status"), now, values);
    }

    /**
     * Worn and held items plus the armour numbers.
     *
     * <p>An empty slot contributes no field at all — "no chestplate" is the absence of the field, which is how
     * the renderer tells it apart from a stack that happens to be empty. When nothing is worn, held or counted,
     * the whole fact is dropped: a fact that says nothing is worse than no fact.</p>
     *
     * <p>No rendered sentence is produced for this fact: equipment is prompt material only, and its wording
     * lives in the prompt templates like every other dynamic fact.</p>
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
        Map<String, String> values = orderedMap();
        values.put("armorPoints", Integer.toString(equipment.armorPoints()));
        values.put("armorToughness", formatDecimal(equipment.armorToughness()));
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
        Map<String, String> values = orderedMap();
        values.put("dimensionId", snapshot.dimensionId());
        values.put("biomeId", environment.biomeId());
        values.put("biomeDisplayName", environment.biomeDisplayName());
        values.put("raining", Boolean.toString(environment.raining()));
        values.put("thundering", Boolean.toString(environment.thundering()));
        values.put("dayTimeTicks", Long.toString(environment.dayTimeTicks()));
        return fact(PresenceContextFactIds.WORLD_ENVIRONMENT, 66, "world", List.of("world", "environment"), now, values);
    }

    private PresenceContextSnapshotPayload.FactPayload inventory(PresenceContextSnapshot snapshot, long now) {
        if (snapshot.inventoryItems().isEmpty()) {
            return null;
        }
        Map<String, InventoryAmount> counts = new LinkedHashMap<>();
        Map<String, String> ids = new LinkedHashMap<>();
        for (PresenceInventoryItem item : snapshot.inventoryItems()) {
            if (item == null || item.count() <= 0) {
                continue;
            }
            String name = readable(item.displayName(), item.itemId());
            InventoryAmount amount = counts.computeIfAbsent(name, ignored -> new InventoryAmount(item.maxStackSize()));
            amount.add(item.count(), item.maxStackSize());
            ids.putIfAbsent(name, item.itemId());
        }
        if (counts.isEmpty()) {
            return null;
        }
        Map<String, String> values = orderedMap();
        values.put("itemCount", Integer.toString(snapshot.inventoryItems().size()));
        values.put("items", counts.entrySet().stream()
                .map(entry -> valueToken(ids.get(entry.getKey())) + ":" + entry.getValue().count)
                .collect(Collectors.joining("|")));
        values.put("inventoryItemsJson", inventoryItemsJson(snapshot.inventoryItems()));
        return fact(PresenceContextFactIds.PLAYER_INVENTORY, 78, "player_inventory", List.of("inventory", "items"), now, values);
    }

    private PresenceContextSnapshotPayload.FactPayload activeEffects(PresenceContextSnapshot snapshot, long now) {
        if (snapshot.activeEffects().isEmpty()) {
            return null;
        }
        // The emptiness rule is "no effect with anything to say", which used to be phrased against the joined
        // sentence. It is now phrased on the effects themselves, so it no longer depends on wording existing.
        boolean named = snapshot.activeEffects().stream()
                .filter(effect -> effect != null && !readable(effect.displayName(), effect.effectId()).isBlank())
                .limit(6)
                .findAny()
                .isPresent();
        if (!named) {
            return null;
        }
        Map<String, String> values = orderedMap();
        values.put("effects", snapshot.activeEffects().stream()
                .filter(effect -> effect != null && !effect.effectId().isBlank())
                .map(effect -> valueToken(effect.effectId()) + ":" + effect.amplifier() + ":" + effect.durationTicks())
                .collect(Collectors.joining("|")));
        values.put("activeEffectsJson", activeEffectsJson(snapshot.activeEffects()));
        return fact(PresenceContextFactIds.PLAYER_ACTIVE_EFFECTS, 76, "player", List.of("player", "effects"), now, values);
    }

    private PresenceContextSnapshotPayload.FactPayload interactionContext(PresenceContextSnapshot snapshot, long now) {
        Map<String, String> values = orderedMap();
        values.put("playerId", snapshot.playerId());
        values.put("dimensionId", snapshot.dimensionId());
        if (!snapshot.equippedItemIds().isEmpty()) {
            values.put("equippedItemIds", snapshot.equippedItemIds().stream()
                    .map(this::valueToken)
                    .collect(Collectors.joining("|")));
        }
        if (!snapshot.heldItemId().isBlank()) {
            values.put("heldItemId", snapshot.heldItemId());
        }
        if (snapshot.screenKind() != PresenceScreenKind.NONE) {
            values.put("screenKind", snapshot.screenKind().name());
        }
        if (snapshot.interactionKeyDown()) {
            values.put("interactionKeyDown", "true");
        }
        if (snapshot.attackKeyDown()) {
            values.put("attackKeyDown", "true");
        }
        if (snapshot.sneaking()) {
            values.put("sneaking", "true");
        }
        PresenceTargetSnapshot target = snapshot.crosshairTarget();
        if (target != null && target.present()) {
            values.put("crosshairTargetId", target.entityId());
            values.put("crosshairTargetTypeId", target.entityTypeId());
            values.put("crosshairTargetDisplayName", target.displayName());
            values.put("crosshairTargetDistance", formatDecimal(target.distance()));
        }
        // Two fields are always written (playerId, dimensionId); anything beyond them is real interaction data.
        if (values.size() <= 2) {
            return null;
        }
        List<FactField> fields = fieldsFrom(PresenceContextFactIds.INTERACTION_CONTEXT, values);
        if (!snapshot.equippedItemIds().isEmpty()) {
            // The aggregate keeps a typed home: it is the only carrier of "which slots hold something", and the
            // arbitration hints read it as a list.
            fields.add(new FactField("equippedItemIds", new FactValue.Text(
                    snapshot.equippedItemIds().stream().map(this::valueToken).collect(Collectors.joining("|")))));
        }
        return fact(PresenceContextFactIds.INTERACTION_CONTEXT, 84, "interaction", List.of("interaction", "screen"), now, fields);
    }

    private PresenceContextSnapshotPayload.FactPayload fact(
            String factId,
            int priority,
            String subject,
            List<String> tags,
            long now,
            Map<String, String> values
    ) {
        return fact(factId, priority, subject, tags, now, fieldsFrom(factId, values));
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

    /**
     * The typed twin of the flat value map.
     * <p>Every fact lists its field names and types explicitly — there is no inference from a name suffix, so a
     * new field cannot silently become the wrong type. The repeated structures (the two JSON blobs) become
     * {@code FactValue.Record} entries below; {@code equippedItemIds} is added by {@link #interactionContext} as
     * a typed field of its own, because arbitration reads it as a list.
     */
    private List<FactField> fieldsFrom(String factId, Map<String, String> values) {
        List<FactField> fields = new ArrayList<>();
        switch (factId) {
            case PresenceContextFactIds.PLAYER_STATUS -> {
                add(fields, "health", values, raw -> new FactValue.Number(numberOf(raw)));
                add(fields, "maxHealth", values, raw -> new FactValue.Number(numberOf(raw)));
                add(fields, "hunger", values, raw -> new FactValue.Count(integerOf(raw)));
                add(fields, "experienceLevel", values, raw -> new FactValue.Count(integerOf(raw)));
                add(fields, "dimensionId", values, FactValue.Identifier::new);
            }
            case PresenceContextFactIds.WORLD_ENVIRONMENT -> {
                add(fields, "dimensionId", values, FactValue.Identifier::new);
                add(fields, "biomeId", values, FactValue.Identifier::new);
                add(fields, "biomeDisplayName", values, FactValue.Text::new);
                add(fields, "raining", values, raw -> new FactValue.Flag(Boolean.parseBoolean(raw)));
                add(fields, "thundering", values, raw -> new FactValue.Flag(Boolean.parseBoolean(raw)));
                add(fields, "dayTimeTicks", values, raw -> new FactValue.Count(integerOf(raw)));
            }
            case PresenceContextFactIds.PLAYER_INVENTORY -> {
                add(fields, "itemCount", values, raw -> new FactValue.Count(integerOf(raw)));
                // The repeated structure is a list, not a scalar: one record per inventory entry, which is the
                // only shape a consumer can iterate without parsing a string.
                fields.addAll(itemRecords(values.get("inventoryItemsJson")));
            }
            case PresenceContextFactIds.PLAYER_ACTIVE_EFFECTS ->
                    fields.addAll(effectRecords(values.get("activeEffectsJson")));
            case PresenceContextFactIds.INTERACTION_CONTEXT -> {
                add(fields, "playerId", values, FactValue.Identifier::new);
                add(fields, "dimensionId", values, FactValue.Identifier::new);
                add(fields, "heldItemId", values, FactValue.Identifier::new);
                add(fields, "screenKind", values, FactValue.Identifier::new);
                add(fields, "interactionKeyDown", values, raw -> new FactValue.Flag(Boolean.parseBoolean(raw)));
                add(fields, "attackKeyDown", values, raw -> new FactValue.Flag(Boolean.parseBoolean(raw)));
                add(fields, "sneaking", values, raw -> new FactValue.Flag(Boolean.parseBoolean(raw)));
                add(fields, "crosshairTargetId", values, FactValue.Identifier::new);
                add(fields, "crosshairTargetTypeId", values, FactValue.Identifier::new);
                add(fields, "crosshairTargetDisplayName", values, FactValue.Text::new);
                add(fields, "crosshairTargetDistance", values, raw -> new FactValue.Number(numberOf(raw)));
            }
            default -> {
            }
        }
        return fields;
    }

    /**
     * One record per inventory entry, named {@code item}.
     *
     * <p>Record members are always present (empty text, zero count) even when the game omitted one, because a
     * record is a fixed shape its reader indexes by name; that is different from a top level field, where a
     * missing value is skipped so a consumer can tell "absent" from "zero".
     */
    private List<FactField> itemRecords(String json) {
        List<FactField> records = new ArrayList<>();
        for (JsonObject item : objects(json)) {
            records.add(new FactField("item", new FactValue.Record(List.of(
                    new FactField("itemId", new FactValue.Identifier(textOf(item, "itemId"))),
                    new FactField("displayName", new FactValue.Text(textOf(item, "displayName"))),
                    new FactField("count", new FactValue.Count(intOf(item, "count"))),
                    new FactField("maxStackSize", new FactValue.Count(intOf(item, "maxStackSize")))))));
        }
        return records;
    }

    /** One record per active effect, named {@code effect}; {@code beneficial} is not read by any consumer. */
    private List<FactField> effectRecords(String json) {
        List<FactField> records = new ArrayList<>();
        for (JsonObject effect : objects(json)) {
            records.add(new FactField("effect", new FactValue.Record(List.of(
                    new FactField("effectId", new FactValue.Identifier(textOf(effect, "effectId"))),
                    new FactField("displayName", new FactValue.Text(textOf(effect, "displayName"))),
                    new FactField("durationTicks", new FactValue.Count(intOf(effect, "durationTicks"))),
                    new FactField("amplifier", new FactValue.Count(intOf(effect, "amplifier")))))));
        }
        return records;
    }

    private List<JsonObject> objects(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed == null || !parsed.isJsonArray()) {
                return List.of();
            }
            List<JsonObject> objects = new ArrayList<>();
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (element != null && element.isJsonObject()) {
                    objects.add(element.getAsJsonObject());
                }
            }
            return objects;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private String textOf(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        try {
            return json.get(key).getAsString().trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private int intOf(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return json.get(key).getAsInt();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /**
     * Adds one field when the producer wrote a value for it.
     *
     * <p>Skipped rather than defaulted on purpose: a missing value means the snapshot did not carry it, and a
     * fabricated {@code 0} would read to a consumer as a real measurement.
     */
    private void add(List<FactField> fields, String name, Map<String, String> values,
                     java.util.function.Function<String, FactValue> factory) {
        String raw = values.get(name);
        if (raw == null || raw.isBlank()) {
            return;
        }
        fields.add(new FactField(name, factory.apply(raw)));
    }

    private double numberOf(String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return 0.0D;
        }
    }

    private int integerOf(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** The game-supplied display name, or the raw id when the game gave none; grouping key only. */
    private String readable(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    private String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private String valueToken(String value) {
        return value == null ? "" : value.trim()
                .replace(";", " ")
                .replace("|", " ");
    }

    private Map<String, String> orderedMap() {
        return new LinkedHashMap<>();
    }

    private String inventoryItemsJson(List<PresenceInventoryItem> items) {
        JsonArray array = new JsonArray();
        if (items != null) {
            for (PresenceInventoryItem item : items) {
                if (item == null || item.count() <= 0) {
                    continue;
                }
                JsonObject json = new JsonObject();
                json.addProperty("itemId", item.itemId());
                json.addProperty("displayName", item.displayName());
                json.addProperty("count", item.count());
                json.addProperty("maxStackSize", item.maxStackSize());
                array.add(json);
            }
        }
        return array.toString();
    }

    private String activeEffectsJson(List<PresencePotionEffect> effects) {
        JsonArray array = new JsonArray();
        if (effects != null) {
            for (PresencePotionEffect effect : effects) {
                if (effect == null || effect.effectId().isBlank()) {
                    continue;
                }
                JsonObject json = new JsonObject();
                json.addProperty("effectId", effect.effectId());
                json.addProperty("displayName", effect.displayName());
                json.addProperty("durationTicks", effect.durationTicks());
                json.addProperty("amplifier", effect.amplifier());
                json.addProperty("beneficial", effect.beneficial());
                array.add(json);
            }
        }
        return array.toString();
    }

    private static final class InventoryAmount {
        private int count;
        private int maxStackSize;

        private InventoryAmount(int maxStackSize) {
            this.maxStackSize = maxStackSize <= 0 ? 64 : maxStackSize;
        }

        private void add(int value, int candidateMaxStackSize) {
            count += Math.max(0, value);
            maxStackSize = Math.max(maxStackSize, candidateMaxStackSize);
        }
    }
}
