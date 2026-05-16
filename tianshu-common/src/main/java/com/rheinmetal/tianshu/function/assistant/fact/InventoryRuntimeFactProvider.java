package com.rheinmetal.tianshu.function.assistant.fact;

import com.rheinmetal.tianshu.function.assistant.AssistantRequest;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;
import com.rheinmetal.tianshu.provider.WorldStateProvider;
import com.rheinmetal.tianshu.snapshot.InventoryItemStackData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class InventoryRuntimeFactProvider implements RuntimeFactProvider {
    private final WorldStateProvider worldStateProvider;

    public InventoryRuntimeFactProvider(WorldStateProvider worldStateProvider) {
        this.worldStateProvider = worldStateProvider;
    }

    @Override
    public String providerId() {
        return "llm.inventory";
    }

    @Override
    public List<RuntimeFact> refreshForQuestion(AssistantScope scope, AssistantRequest request) {
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
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (InventoryItemStackData item : items) {
            if (item == null || item.count() <= 0) {
                continue;
            }
            counts.merge(item.displayOrId(), item.count(), Integer::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + "x" + entry.getValue())
                .collect(Collectors.joining("，"));
    }

}
