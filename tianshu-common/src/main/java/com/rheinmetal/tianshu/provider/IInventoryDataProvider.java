package com.rheinmetal.tianshu.provider;

import com.rheinmetal.tianshu.snapshot.*;

import java.util.List;

public interface IInventoryDataProvider {

    ItemSnapshot getMainHandItemData();

    List<ItemSnapshot> getAllInventoryItemsData();

    List<MatchedSlotData> findItemSlotsByName(String namePattern);

    List<ItemSnapshot> findItemsByCategory(String category);

    boolean isProtectedItem(ItemSnapshot item);
}
