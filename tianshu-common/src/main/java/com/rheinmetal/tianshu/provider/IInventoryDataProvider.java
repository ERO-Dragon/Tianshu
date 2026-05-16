package com.rheinmetal.tianshu.provider;

import com.rheinmetal.tianshu.snapshot.*;

import java.util.List;

public interface IInventoryDataProvider {

    ItemSnapshot getMainHandItemData();

    List<ItemSnapshot> getAllInventoryItemsData();

    default List<InventoryItemStackData> getInventoryItemStacksData() {
        List<ItemSnapshot> snapshots = getAllInventoryItemsData();
        if (snapshots == null || snapshots.isEmpty()) {
            return List.of();
        }
        return snapshots.stream()
                .filter(item -> item != null && item.getCount() > 0)
                .map(item -> new InventoryItemStackData(item.getItemId(), item.getDisplayName(), item.getCount()))
                .toList();
    }

    List<MatchedSlotData> findItemSlotsByName(String namePattern);

    List<ItemSnapshot> findItemsByCategory(String category);

    boolean isProtectedItem(ItemSnapshot item);
}
