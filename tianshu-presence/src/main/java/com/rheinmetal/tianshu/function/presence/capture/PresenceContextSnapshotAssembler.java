package com.rheinmetal.tianshu.function.presence.capture;

import com.rheinmetal.tianshu.client.host.game.ClientGameContextPort;
import com.rheinmetal.tianshu.client.host.game.GameEffect;
import com.rheinmetal.tianshu.client.host.game.GameEquipment;
import com.rheinmetal.tianshu.client.host.game.GameInteractionContext;
import com.rheinmetal.tianshu.client.host.game.GameInventoryEntry;
import com.rheinmetal.tianshu.client.host.game.GamePlayerStatus;
import com.rheinmetal.tianshu.client.host.game.GameScreenKind;
import com.rheinmetal.tianshu.client.host.game.GameTarget;
import com.rheinmetal.tianshu.client.host.game.GameWorldEnvironment;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextGroup;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceEquipment;
import com.rheinmetal.tianshu.client.presence.model.PresenceInventoryItem;
import com.rheinmetal.tianshu.client.presence.model.PresencePlayerStatus;
import com.rheinmetal.tianshu.client.presence.model.PresencePotionEffect;
import com.rheinmetal.tianshu.client.presence.model.PresenceScreenKind;
import com.rheinmetal.tianshu.client.presence.model.PresenceTargetSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceWorldEnvironment;

import java.util.List;
import java.util.Set;

/**
 * Turns the platform's neutral game reading into presence's own snapshot.
 *
 * <p>Only the groups the caller asked for are read: the per-tick path asks for the interaction slice alone, so a
 * crosshair watcher never pays for walking the inventory or the effect list. The mapping lives on the consumer
 * side on purpose — the api holds game values, presence decides what they mean, and the platform never sees a
 * presence-shaped model.</p>
 */
public final class PresenceContextSnapshotAssembler {
    public PresenceContextSnapshot assemble(ClientGameContextPort port, Set<PresenceContextGroup> groups) {
        Set<PresenceContextGroup> requested = groups == null || groups.isEmpty()
                ? Set.of(PresenceContextGroup.INTERACTION_CONTEXT)
                : groups;
        GameInteractionContext interaction = requested.contains(PresenceContextGroup.INTERACTION_CONTEXT)
                ? port.captureInteraction()
                : GameInteractionContext.empty();
        GamePlayerStatus status = requested.contains(PresenceContextGroup.PLAYER_STATUS)
                ? port.capturePlayerStatus()
                : GamePlayerStatus.EMPTY;
        List<GameInventoryEntry> inventory = requested.contains(PresenceContextGroup.PLAYER_INVENTORY)
                ? port.captureInventory()
                : List.of();
        GameEquipment equipment = requested.contains(PresenceContextGroup.PLAYER_EQUIPMENT)
                ? port.captureEquipment()
                : GameEquipment.EMPTY;
        List<GameEffect> effects = requested.contains(PresenceContextGroup.PLAYER_ACTIVE_EFFECTS)
                ? port.captureEffects()
                : List.of();
        GameWorldEnvironment environment = requested.contains(PresenceContextGroup.WORLD_ENVIRONMENT)
                ? port.captureEnvironment()
                : GameWorldEnvironment.EMPTY;
        return new PresenceContextSnapshot(
                port.playerId(),
                interaction.dimensionId(),
                interaction.dimensionDisplayName(),
                screenKind(interaction.screenKind()),
                interaction.heldItemId(),
                interaction.heldItemDisplayName(),
                interaction.equippedItemIds(),
                target(interaction.crosshair()),
                interaction.useKeyDown(),
                interaction.attackKeyDown(),
                interaction.sneaking(),
                new PresencePlayerStatus(status.health(), status.maxHealth(), status.hunger(), status.experienceLevel()),
                new PresenceWorldEnvironment(environment.raining(), environment.thundering(), environment.dayTime(),
                        environment.biomeId(), environment.biomeDisplayName()),
                inventoryEntries(inventory),
                equipment(equipment),
                effects(effects),
                interaction.capturedAtMillis()
        );
    }

    private PresenceScreenKind screenKind(GameScreenKind kind) {
        return switch (kind == null ? GameScreenKind.NONE : kind) {
            case NONE -> PresenceScreenKind.NONE;
            case PAUSE -> PresenceScreenKind.PAUSE;
            case CHAT -> PresenceScreenKind.CHAT;
            case INVENTORY -> PresenceScreenKind.INVENTORY;
            case CONTAINER -> PresenceScreenKind.CONTAINER;
            case SETTINGS -> PresenceScreenKind.SETTINGS;
            case OTHER -> PresenceScreenKind.OTHER;
        };
    }

    private PresenceTargetSnapshot target(GameTarget target) {
        if (target == null || !target.present()) {
            return PresenceTargetSnapshot.empty();
        }
        return new PresenceTargetSnapshot(
                target.entityId(),
                target.entityTypeId(),
                target.displayName(),
                target.distance(),
                true
        );
    }

    private PresenceEquipment equipment(GameEquipment equipment) {
        if (equipment == null) {
            return PresenceEquipment.empty();
        }
        return new PresenceEquipment(
                entry(equipment.helmet()),
                entry(equipment.chestplate()),
                entry(equipment.leggings()),
                entry(equipment.boots()),
                entry(equipment.mainHand()),
                entry(equipment.offHand()),
                equipment.armorPoints(),
                equipment.armorToughness()
        );
    }

    private List<PresenceInventoryItem> inventoryEntries(List<GameInventoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream().map(this::entry).toList();
    }

    private PresenceInventoryItem entry(GameInventoryEntry entry) {
        if (entry == null) {
            return null;
        }
        return new PresenceInventoryItem(entry.itemId(), entry.displayName(), entry.count(), entry.maxStackSize());
    }

    private List<PresencePotionEffect> effects(List<GameEffect> effects) {
        if (effects == null || effects.isEmpty()) {
            return List.of();
        }
        return effects.stream()
                .map(effect -> new PresencePotionEffect(effect.effectId(), effect.displayName(),
                        effect.durationTicks(), effect.amplifier(), effect.beneficial()))
                .toList();
    }
}