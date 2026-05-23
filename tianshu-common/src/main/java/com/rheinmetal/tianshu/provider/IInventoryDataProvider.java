package com.rheinmetal.tianshu.provider;

import com.rheinmetal.tianshu.snapshot.InventoryItemStackData;

import java.util.List;

public interface IInventoryDataProvider {

    List<InventoryItemStackData> getInventoryItemStacksData();
}
