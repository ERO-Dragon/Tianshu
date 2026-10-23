package com.rheinmetal.tianshu.client.presence.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceInventoryItem;
import com.rheinmetal.tianshu.client.presence.model.PresencePlayerStatus;
import com.rheinmetal.tianshu.client.presence.model.PresencePotionEffect;
import com.rheinmetal.tianshu.client.presence.model.PresenceScreenKind;
import com.rheinmetal.tianshu.client.presence.model.PresenceTargetSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceWorldEnvironment;
import com.rheinmetal.tianshu.client.presence.PresenceTextProvider;
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
    /**
     * Values that are aggregate tokens rather than scalars, so the transitional field bridge skips them; step ②
     * turns each of them into a repeated {@code FactValue.Record}.
     */
    private static final Set<String> AGGREGATE_TOKENS =
            Set.of("items", "effects", "inventoryItemsJson", "activeEffectsJson", "equippedItemIds");
    private final PresenceTextProvider textProvider;

    public PresenceContextFactMapper() {
        this(PresenceTextProvider.NOOP);
    }

    public PresenceContextFactMapper(PresenceTextProvider textProvider) {
        this.textProvider = textProvider == null ? PresenceTextProvider.NOOP : textProvider;
    }

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
        String text = tr("tianshu.presence.context.player_status",
                formatDecimal(status.health()),
                formatDecimal(status.maxHealth()),
                status.hunger(),
                status.experienceLevel());
        return fact(PresenceContextFactIds.PLAYER_STATUS, text, 82, "player", List.of("player", "status"), now, values);
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
        String weatherKey = environment.thundering()
                ? "tianshu.presence.context.weather.thunder"
                : environment.raining()
                ? "tianshu.presence.context.weather.rain"
                : "tianshu.presence.context.weather.clear";
        String text = tr("tianshu.presence.context.world_environment",
                readableDimension(snapshot.dimensionId()),
                readable(environment.biomeDisplayName(), environment.biomeId()),
                tr(weatherKey),
                environment.dayTimeTicks());
        return fact(PresenceContextFactIds.WORLD_ENVIRONMENT, text, 66, "world", List.of("world", "environment"), now, values);
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
        String itemsText = counts.entrySet().stream()
                .limit(8)
                .map(entry -> tr("tianshu.presence.context.inventory.item", entry.getKey(), entry.getValue().count))
                .collect(Collectors.joining(tr("tianshu.presence.context.list_separator")));
        Map<String, String> values = orderedMap();
        values.put("itemCount", Integer.toString(snapshot.inventoryItems().size()));
        values.put("items", counts.entrySet().stream()
                .map(entry -> valueToken(ids.get(entry.getKey())) + ":" + entry.getValue().count)
                .collect(Collectors.joining("|")));
        values.put("inventoryItemsJson", inventoryItemsJson(snapshot.inventoryItems()));
        String text = tr("tianshu.presence.context.inventory", itemsText);
        return fact(PresenceContextFactIds.PLAYER_INVENTORY, text, 78, "player_inventory", List.of("inventory", "items"), now, values);
    }

    private PresenceContextSnapshotPayload.FactPayload activeEffects(PresenceContextSnapshot snapshot, long now) {
        if (snapshot.activeEffects().isEmpty()) {
            return null;
        }
        String effectsText = snapshot.activeEffects().stream()
                .filter(effect -> effect != null && !readable(effect.displayName(), effect.effectId()).isBlank())
                .limit(6)
                .map(effect -> tr("tianshu.presence.context.effect.item",
                        readable(effect.displayName(), effect.effectId()),
                        effect.amplifier() + 1,
                        Math.max(0, effect.durationTicks() / 20)))
                .collect(Collectors.joining(tr("tianshu.presence.context.list_separator")));
        if (effectsText.isBlank()) {
            return null;
        }
        Map<String, String> values = orderedMap();
        values.put("effects", snapshot.activeEffects().stream()
                .filter(effect -> effect != null && !effect.effectId().isBlank())
                .map(effect -> valueToken(effect.effectId()) + ":" + effect.amplifier() + ":" + effect.durationTicks())
                .collect(Collectors.joining("|")));
        values.put("activeEffectsJson", activeEffectsJson(snapshot.activeEffects()));
        String text = tr("tianshu.presence.context.effects", effectsText);
        return fact(PresenceContextFactIds.PLAYER_ACTIVE_EFFECTS, text, 76, "player", List.of("player", "effects"), now, values);
    }

    private PresenceContextSnapshotPayload.FactPayload interactionContext(PresenceContextSnapshot snapshot, long now) {
        List<String> parts = new ArrayList<>();
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
            parts.add(tr("tianshu.presence.context.interaction.held_item", readableId(snapshot.heldItemId())));
        }
        if (snapshot.screenKind() != PresenceScreenKind.NONE) {
            values.put("screenKind", snapshot.screenKind().name());
            parts.add(tr("tianshu.presence.context.interaction.screen", screenName(snapshot.screenKind())));
        }
        if (snapshot.interactionKeyDown()) {
            values.put("interactionKeyDown", "true");
            parts.add(tr("tianshu.presence.context.interaction.key_down"));
        }
        if (snapshot.attackKeyDown()) {
            values.put("attackKeyDown", "true");
            parts.add(tr("tianshu.presence.context.interaction.attack_key_down"));
        }
        if (snapshot.sneaking()) {
            values.put("sneaking", "true");
            parts.add(tr("tianshu.presence.context.interaction.sneaking"));
        }
        PresenceTargetSnapshot target = snapshot.crosshairTarget();
        if (target != null && target.present()) {
            values.put("crosshairTargetId", target.entityId());
            values.put("crosshairTargetTypeId", target.entityTypeId());
            values.put("crosshairTargetDisplayName", target.displayName());
            values.put("crosshairTargetDistance", formatDecimal(target.distance()));
            parts.add(tr("tianshu.presence.context.interaction.crosshair",
                    readable(target.displayName(), readableId(target.entityTypeId())),
                    formatDecimal(target.distance())));
        }
        if (parts.isEmpty() && values.size() <= 2) {
            return null;
        }
        String text = parts.isEmpty()
                ? ""
                : tr("tianshu.presence.context.interaction",
                String.join(tr("tianshu.presence.context.list_separator"), parts));
        return fact(PresenceContextFactIds.INTERACTION_CONTEXT, text, 84, "interaction", List.of("interaction", "screen"), now, values);
    }

    private PresenceContextSnapshotPayload.FactPayload fact(
            String factId,
            String text,
            int priority,
            String subject,
            List<String> tags,
            long now,
            Map<String, String> nativeValues
    ) {
        return new PresenceContextSnapshotPayload.FactPayload(
                factId,
                // Structured twin of the flat map below; see fieldsFrom for why both are written for now.
                fieldsFrom(nativeValues),
                text,
                priority,
                "presence",
                subject,
                tags,
                now,
                FACT_TTL_MILLIS,
                nativeValues
        );
    }

    /**
     * The typed twin of the flat value map.
     *
     * <p>Transitional bridge for step ①: the flat map still has readers while they are migrated, so the
     * producer writes both. The types are derived from the field names this producer itself defines; step ②
     * replaces this method with the per-fact field lists, at which point the aggregate tokens
     * ({@link #AGGREGATE_TOKENS}) become proper {@code FactValue.Record} values instead of being skipped.
     */
    private List<FactField> fieldsFrom(Map<String, String> values) {
        List<FactField> fields = new ArrayList<>(values.size());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = entry.getKey();
            String raw = entry.getValue();
            if (name == null || raw == null || raw.isBlank() || AGGREGATE_TOKENS.contains(name)) {
                continue;
            }
            fields.add(new FactField(name, typedValue(name, raw)));
        }
        return fields;
    }

    private FactValue typedValue(String name, String raw) {
        if (name.endsWith("Id") || "screenKind".equals(name)) {
            return new FactValue.Identifier(raw);
        }
        if (name.endsWith("DisplayName")) {
            return new FactValue.Text(raw);
        }
        if ("raining".equals(name) || "thundering".equals(name) || "sneaking".equals(name)
                || "beneficial".equals(name) || name.endsWith("KeyDown")) {
            return new FactValue.Flag(Boolean.parseBoolean(raw));
        }
        if ("health".equals(name) || "maxHealth".equals(name) || "crosshairTargetDistance".equals(name)) {
            return new FactValue.Number(numberOf(raw));
        }
        return new FactValue.Count(integerOf(raw));
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

    private String screenName(PresenceScreenKind kind) {
        PresenceScreenKind effective = kind == null ? PresenceScreenKind.NONE : kind;
        return tr("tianshu.presence.context.screen." + effective.name().toLowerCase(Locale.ROOT));
    }

    private String readableDimension(String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return tr("tianshu.presence.context.unknown");
        }
        String key = "tianshu.presence.context.dimension." + dimensionId.trim().replace(':', '.');
        if (textProvider.exists(key)) {
            return textProvider.text(key);
        }
        return readableId(dimensionId);
    }

    private String readableId(String value) {
        if (value == null || value.isBlank()) {
            return tr("tianshu.presence.context.unknown");
        }
        String normalized = value.trim();
        int separator = normalized.indexOf(':');
        if (separator >= 0 && separator < normalized.length() - 1) {
            normalized = normalized.substring(separator + 1);
        }
        return normalized.replace('_', ' ').replace('-', ' ');
    }

    private String readable(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    private String tr(String key, Object... args) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (textProvider.exists(key)) {
            return textProvider.text(key, args);
        }
        return PresenceTextProvider.NOOP.text(key, args);
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
