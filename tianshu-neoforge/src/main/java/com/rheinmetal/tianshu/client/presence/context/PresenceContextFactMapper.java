package com.rheinmetal.tianshu.client.presence.context;

import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceInventoryItem;
import com.rheinmetal.tianshu.client.presence.model.PresencePlayerStatus;
import com.rheinmetal.tianshu.client.presence.model.PresencePotionEffect;
import com.rheinmetal.tianshu.client.presence.model.PresenceScreenKind;
import com.rheinmetal.tianshu.client.presence.model.PresenceTargetSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceWorldEnvironment;
import com.rheinmetal.tianshu.platform.PresenceTextProvider;
import com.rheinmetal.tianshu.protocol.PresenceContextFactIds;
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
