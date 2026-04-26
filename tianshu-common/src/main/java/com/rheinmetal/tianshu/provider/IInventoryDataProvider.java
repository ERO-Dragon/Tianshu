package com.rheinmetal.tianshu.provider;

import com.rheinmetal.tianshu.snapshot.InventorySnapshot;
import com.rheinmetal.tianshu.snapshot.ItemSnapshot;
import com.rheinmetal.tianshu.snapshot.MatchedSlotData;

import java.util.List;

public interface IInventoryDataProvider {
    ItemSnapshot getMainHandItemData();

    InventorySnapshot getAllInventoryItemsData();

    List<MatchedSlotData> findItemSlotsByName(String name);
}
