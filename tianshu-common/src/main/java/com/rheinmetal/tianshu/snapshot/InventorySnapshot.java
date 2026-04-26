package com.rheinmetal.tianshu.snapshot;

import java.util.*;

public final class InventorySnapshot {

    public final List<ItemSnapshot> items;
    public final int mainHandSlot;
    public final ItemSnapshot mainHand;
    public final ItemSnapshot offHand;
    public final List<ItemSnapshot> armor;

    public InventorySnapshot(
            List<ItemSnapshot> items,
            int mainHandSlot,
            ItemSnapshot mainHand,
            ItemSnapshot offHand,
            List<ItemSnapshot> armor
    ) {
        this.items = items != null ? items : Collections.emptyList();
        this.mainHandSlot = mainHandSlot;
        this.mainHand = mainHand;
        this.offHand = offHand;
        this.armor = armor != null ? armor : Collections.emptyList();
    }

    public List<ItemSnapshot> getItems() { return items; }
    public int getMainHandSlot() { return mainHandSlot; }
    public ItemSnapshot getMainHand() { return mainHand; }
    public ItemSnapshot getOffHand() { return offHand; }
    public List<ItemSnapshot> getArmor() { return armor; }
}
