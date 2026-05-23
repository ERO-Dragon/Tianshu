package com.rheinmetal.tianshu.function.auxilium.fact;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.provider.WorldStateProvider;
import com.rheinmetal.tianshu.snapshot.InventoryItemStackData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InventoryRuntimeFactProvider extends AbstractDirtyRuntimeFactProvider {
    private final WorldStateProvider worldStateProvider;

    public InventoryRuntimeFactProvider(WorldStateProvider worldStateProvider) {
        this.worldStateProvider = worldStateProvider;
    }

    @Override
    public String providerId() {
        return "llm.inventory";
    }

    @Override
    protected String snapshotSignature(AXScope scope, AXRequest request) {
        if (worldStateProvider == null || worldStateProvider.getInventory() == null) {
            return "inventory:empty";
        }
        List<InventoryItemStackData> items = worldStateProvider.getInventory().getInventoryItemStacksData();
        if (items == null || items.isEmpty()) {
            return "inventory:empty";
        }
        return itemCounts(items);
    }

    @Override
    protected List<RuntimeFact> collectFacts(AXScope scope, AXRequest request) {
        if (worldStateProvider == null || worldStateProvider.getInventory() == null) {
            return List.of();
        }
        List<InventoryItemStackData> items = worldStateProvider.getInventory().getInventoryItemStacksData();
        if (items == null || items.isEmpty()) {
            return List.of(new RuntimeFact(
                    "fact.player.inventory.items",
                    "inventory_items",
                    providerId(),
                    "player_inventory",
                    Map.of("items", ""),
                    List.of("inventory"),
                    70,
                    System.currentTimeMillis(),
                    120_000L,
                    System.currentTimeMillis()
            ));
        }
        return List.of(new RuntimeFact(
                "fact.player.inventory.items",
                "inventory_items",
                providerId(),
                "player_inventory",
                Map.of("items", itemCounts(items)),
                List.of("inventory", "items"),
                78,
                System.currentTimeMillis(),
                120_000L,
                System.currentTimeMillis()
        ));
    }

    private String itemCounts(List<InventoryItemStackData> items) {
        Map<String, InventoryItemAmount> counts = new LinkedHashMap<>();
        for (InventoryItemStackData item : items) {
            if (item == null || item.count() <= 0) {
                continue;
            }
            String name = item.displayOrId();
            InventoryItemAmount amount = counts.computeIfAbsent(name, ignored -> new InventoryItemAmount(item.maxStackSize()));
            amount.add(item.count(), item.maxStackSize());
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, InventoryItemAmount> entry : counts.entrySet()) {
            if (builder.length() > 0) {
                builder.append("|");
            }
            builder.append(escape(entry.getKey())).append(";").append(entry.getValue().count).append(";").append(entry.getValue().maxStackSize);
        }
        return builder.toString();
    }

    private String escape(String value) {
        return value == null ? "" : value.trim().replace(";", " ").replace("|", " ");
    }

    private static final class InventoryItemAmount {
        private int count;
        private int maxStackSize;

        private InventoryItemAmount(int maxStackSize) {
            this.maxStackSize = maxStackSize <= 0 ? 64 : maxStackSize;
        }

        private void add(int count, int maxStackSize) {
            this.count += Math.max(0, count);
            if (maxStackSize > this.maxStackSize) {
                this.maxStackSize = maxStackSize;
            }
        }
    }

}
